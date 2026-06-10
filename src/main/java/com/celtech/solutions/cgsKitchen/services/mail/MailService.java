package com.celtech.solutions.cgsKitchen.services.mail;

import com.celtech.solutions.cgsKitchen.models.mail.MailMessage;

public interface MailService {
    void send(MailMessage message);
}
