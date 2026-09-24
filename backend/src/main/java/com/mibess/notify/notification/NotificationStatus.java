package com.mibess.notify.notification;

public enum NotificationStatus {
    PENDING,QUEUED,PROCESSING,SENT,DELIVERED,READ,FAILED,CANCELLED;
    public boolean accepts(NotificationStatus next) {
        if(this==CANCELLED||this==READ||this==next)return false;
        return switch(next){case SENT->this==PROCESSING||this==FAILED;case DELIVERED->this==PROCESSING||this==SENT||this==FAILED;case READ->this==PROCESSING||this==SENT||this==DELIVERED||this==FAILED;case FAILED->this==PROCESSING||this==SENT;default->false;};
    }
}
