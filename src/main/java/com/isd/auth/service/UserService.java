package com.isd.auth.service;

import java.security.SecureRandom;
import java.util.List;

import com.isd.auth.domain.UserRole;
import com.isd.auth.dto.AdminContactResponse;
import com.isd.auth.dto.ChangePasswordRequest;
import com.isd.auth.dto.RegisterRequest;
import com.isd.auth.dto.ResetPasswordResponse;
import com.isd.auth.dto.UpdateContactRequest;
import com.isd.auth.dto.UserResponse;
import com.isd.auth.entity.User;
import com.isd.auth.mapper.UserMapper;
import com.isd.auth.security.SessionRevoker;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /**
     * 临时密码字符集：去掉 O/0/I/l/1 这些抄写时容易看错的字符。
     * 12 位 × 57 字符集，熵足够撑住内网重置场景。
     */
    private static final char[] TEMP_PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final int TEMP_PASSWORD_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SessionRevoker sessionRevoker;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder, SessionRevoker sessionRevoker) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionRevoker = sessionRevoker;
    }

    /**
     * 注册：角色固定为 EMPLOYEE。
     * 为什么不接受前端传 role：注册接口一旦允许指定角色，任何人注册时写 role=ADMIN 就直接提权。
     * 提权只能由 ADMIN 通过后台操作完成。
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userMapper.findByUsername(request.username()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }
        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(isBlank(request.fullName()) ? request.username() : request.fullName().trim());
        user.setRole(UserRole.EMPLOYEE);
        user.setStatus(1);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            // 上面的存在性检查与插入之间仍可能被并发插队（检查-执行竞态），
            // 唯一索引才是最终裁判：把它翻译成 409，而不是让兜底处理成 500。
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }
        return UserResponse.from(reload(user.getId()));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userMapper.findAll().stream().map(UserResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getById(Long id) {
        return UserResponse.from(requireUser(id));
    }

    /**
     * 登录页“忘记密码”的数据源：只返回启用中、且至少填写了一种联系方式的管理员。
     * 不返回用户名/id，未登录的人只需要知道找谁、怎么联系。
     */
    @Transactional(readOnly = true)
    public List<AdminContactResponse> listAdminContacts() {
        return userMapper.findEnabledAdmins().stream()
                .filter(user -> !isBlank(user.getPhone()) || !isBlank(user.getEmail()))
                .map(AdminContactResponse::from)
                .toList();
    }

    /**
     * 维护自己的联系方式。
     * 管理员必须至少保留一项，否则登录页的“忘记密码”对员工没有任何可用信息。
     * 不 bump version：联系方式与角色/状态的并发控制无关。
     */
    @Transactional
    public UserResponse updateContact(Long userId, UpdateContactRequest request) {
        User user = requireUser(userId);
        String phone = normalize(request.phone());
        String email = normalize(request.email());
        if (user.getRole() == UserRole.ADMIN && phone == null && email == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "管理员请至少填写手机号或邮箱，方便忘记密码的用户联系你");
        }
        userMapper.updateContact(userId, phone, email);
        log.info("联系方式更新: userId={} phoneSet={} emailSet={}", userId, phone != null, email != null);
        return UserResponse.from(requireUser(userId));
    }

    /**
     * 管理员重置密码：生成一次性临时密码，置强制改密标记，并踢掉目标用户所有会话。
     *
     * 安全边界：
     * 1. 不能重置自己（与“不能改自己角色/状态”一致，忘记自己密码只能找另一位管理员）；
     * 2. 明文只出现在本次响应里，日志只记 id；
     * 3. 不改变角色/启用状态，不 bump version。
     */
    @Transactional
    public ResetPasswordResponse resetPassword(Long targetId, Long operatorId) {
        if (targetId.equals(operatorId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "不能重置自己的密码，请让其他管理员操作");
        }
        User target = requireUser(targetId);
        String temporaryPassword = generateTemporaryPassword();

        int updated = userMapper.updatePassword(targetId, passwordEncoder.encode(temporaryPassword), 1);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: id=" + targetId);
        }
        // 旧会话必须立刻失效，否则对方仍可用旧会话继续操作，绕过强制改密。
        // 按 username 查：Spring Session 的 principal 索引是登录名，不是 userId
        sessionRevoker.revokeAll(target.getUsername());

        log.info("密码重置: targetId={} username={} operatorId={}", targetId, target.getUsername(), operatorId);
        return new ResetPasswordResponse(targetId, target.getUsername(), temporaryPassword, true);
    }

    /**
     * 修改自己的密码（首次登录强制改密也走这里）。
     * 原密码错误用 400：401 会被前端当成会话失效踢回登录页，用户只是输错字段，应该留在表单。
     * 改完清掉 must_change_password，并失效该用户的其他会话（当前会话由 Controller 续期）。
     */
    @Transactional
    public UserResponse changePassword(Long userId, ChangePasswordRequest request, String currentSessionId) {
        User user = requireUser(userId);
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "原密码不正确");
        }
        if (request.oldPassword().equals(request.newPassword())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "新密码不能与原密码相同");
        }

        userMapper.updatePassword(userId, passwordEncoder.encode(request.newPassword()), 0);
        sessionRevoker.revokeOthers(user.getUsername(), currentSessionId);
        log.info("密码修改: userId={}", userId);
        return UserResponse.from(requireUser(userId));
    }

    @Transactional
    public UserResponse updateStatus(Long targetId, Integer status, Long operatorId) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "status 只能是 0（禁用）或 1（启用）");
        }
        if (targetId.equals(operatorId)) {
            // 自己禁用自己会把系统锁死，属于业务规则不允许
            throw new BusinessException(ErrorCode.CONFLICT, "不能修改自己的启用状态");
        }
        User target = userMapper.findById(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: id=" + targetId));
        if (Integer.valueOf(status).equals(target.getStatus())) {
            return UserResponse.from(target);
        }
        if (status == 0) {
            // 禁用管理员必须先过"至少保留一个"的跨行不变量检查
            ensureNotRemovingLastAdmin(target, "禁用");
        }

        int updated = userMapper.updateStatus(targetId, status, target.getVersion());
        if (updated == 0) {
            // 别人已经改成了目标值：按幂等成功返回，避免两个管理员做同一件事时互相报错。
            // 必须用加锁读拿最新已提交值，普通 SELECT 在 REPEATABLE READ 下仍是旧快照
            User latest = userMapper.findByIdForUpdate(targetId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: id=" + targetId));
            if (Integer.valueOf(status).equals(latest.getStatus())) {
                return UserResponse.from(latest);
            }
            throw new BusinessException(ErrorCode.CONFLICT, "该账号已被其他管理员修改，请刷新后重试");
        }
        log.info("状态变更: id={} username={} status {} -> {} (operator={})",
                targetId, target.getUsername(), target.getStatus(), status, operatorId);
        return UserResponse.from(reload(targetId));
    }

    /**
     * 调整角色（EMPLOYEE / SUPPORT / ADMIN）。
     *
     * 为什么禁止改自己：把自己降成员工会立刻失去管理入口，属于不可逆的误操作，
     * 与"不能禁用自己"是同一条保护逻辑，需要由另一个管理员来操作。
     *
     * 注意：角色存在 Session 里，改完不会踢掉对方当前会话，必须重新登录才生效。
     * 这是 Session 方案的已知行为，前端需要在成功提示里说清楚。
     */
    @Transactional
    public UserResponse updateRole(Long targetId, String roleRaw, Long operatorId) {
        UserRole role = UserRole.of(roleRaw);
        if (targetId.equals(operatorId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "不能修改自己的角色，请让其他管理员操作");
        }
        User target = userMapper.findById(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: id=" + targetId));
        if (target.getRole() == role) {
            return UserResponse.from(target);
        }
        if (role != UserRole.ADMIN) {
            ensureNotRemovingLastAdmin(target, "降级");
        }

        int updated = userMapper.updateRole(targetId, role.name(), target.getVersion());
        if (updated == 0) {
            User latest = userMapper.findByIdForUpdate(targetId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: id=" + targetId));
            // 幂等：两个管理员都把角色设成 SUPPORT 时，后到的一个不应该看到冲突
            if (latest.getRole() == role) {
                return UserResponse.from(latest);
            }
            throw new BusinessException(ErrorCode.CONFLICT, "该账号的角色已被其他管理员修改，请刷新后重试");
        }
        log.info("角色变更: id={} username={} {} -> {} (operator={})",
                targetId, target.getUsername(), target.getRole(), role, operatorId);
        return UserResponse.from(reload(targetId));
    }

    /**
     * "至少保留一个启用状态的管理员"是跨行不变量，单行乐观锁管不住，
     * 必须在同一事务里先按 id 顺序锁住所有启用管理员，再判断。
     *
     * 这样两个管理员同时互相降权时：先到的事务正常提交，后到的那个看到只剩自己并拒绝，
     * 不会出现"两个人都成功、系统没有管理员"的结局。
     */
    private void ensureNotRemovingLastAdmin(User target, String action) {
        if (target.getRole() != UserRole.ADMIN || !Integer.valueOf(1).equals(target.getStatus())) {
            return;
        }
        List<Long> adminIds = userMapper.lockEnabledAdminIds();
        if (adminIds.size() <= 1 && adminIds.contains(target.getId())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "系统至少需要保留一个启用状态的管理员，无法" + action + "该账号");
        }
    }

    private User reload(Long id) {
        return userMapper.findById(id)
                .orElseThrow(() -> new IllegalStateException("用户回查失败，id=" + id));
    }

    private User requireUser(Long id) {
        return userMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: id=" + id));
    }

    private String normalize(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String generateTemporaryPassword() {
        StringBuilder builder = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            builder.append(TEMP_PASSWORD_CHARS[RANDOM.nextInt(TEMP_PASSWORD_CHARS.length)]);
        }
        return builder.toString();
    }
}
