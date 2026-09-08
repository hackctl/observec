package com.hackctl.observec.engine.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class AlertmanagerNotificationStore {

    private static final int MAX_NOTIFICATIONS = 200;

    private final Deque<AlertmanagerNotification> notifications = new ArrayDeque<>();

    public synchronized void add(AlertmanagerNotification notification) {
        if (notifications.size() == MAX_NOTIFICATIONS) {
            notifications.pollFirst();
        }
        notifications.addLast(notification);
    }

    public synchronized List<AlertmanagerNotification> listRecent(int maxItems) {
        List<AlertmanagerNotification> recent = new ArrayList<>(notifications);
        if (recent.size() <= maxItems) {
            return recent;
        }
        return recent.subList(recent.size() - maxItems, recent.size());
    }

    public record AlertmanagerNotification(
            Instant receivedAt,
            String status,
            String alertname,
            String instance,
            String severity,
            String summary) {
    }
}
