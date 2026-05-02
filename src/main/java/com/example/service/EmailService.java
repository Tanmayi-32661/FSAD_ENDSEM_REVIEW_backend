package com.example.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
  private final JavaMailSender mailSender;
  private final String from;
  private final String frontendUrl;

  public EmailService(ObjectProvider<JavaMailSender> mailSenderProvider,
                      @Value("${app.mail.from:PlaceIT Hub <no-reply@placeit.local>}") String from,
                      @Value("${app.frontend.url:http://localhost:8080}") String frontendUrl) {
    this.mailSender = mailSenderProvider.getIfAvailable();
    this.from = from;
    this.frontendUrl = frontendUrl;
  }

  public String getFrontendUrl() {
    return frontendUrl;
  }

  public void send(String to, String subject, String body) {
    if (to == null || to.isBlank()) {
      return;
    }

    if (mailSender == null) {
      logEmail(to, subject, body);
      return;
    }

    try {
      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom(from);
      message.setTo(to);
      message.setSubject(subject);
      message.setText(body);
      mailSender.send(message);
    } catch (MailException error) {
      System.out.println("[EMAIL FALLBACK] SMTP send failed: " + error.getMessage());
      logEmail(to, subject, body);
    }
  }

  private void logEmail(String to, String subject, String body) {
    System.out.println();
    System.out.println("========== PLACEIT EMAIL ==========");
    System.out.println("To: " + to);
    System.out.println("Subject: " + subject);
    System.out.println(body);
    System.out.println("===================================");
    System.out.println();
  }
}
