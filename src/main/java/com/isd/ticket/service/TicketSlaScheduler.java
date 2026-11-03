package com.isd.ticket.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class TicketSlaScheduler {
    private static final Logger log = LoggerFactory.getLogger(TicketSlaScheduler.class);
    private final TicketService tickets;
    public TicketSlaScheduler(TicketService tickets) { this.tickets = tickets; }

    @Scheduled(fixedDelayString="${isd.ticket.sla.interval-ms:60000}", initialDelayString="${isd.ticket.sla.initial-delay-ms:60000}")
    public void remind() {
        try { tickets.sendSlaReminders(); }
        catch (RuntimeException ex) { log.warn("工单时限提醒本轮失败，稍后重试", ex); }
    }
}
