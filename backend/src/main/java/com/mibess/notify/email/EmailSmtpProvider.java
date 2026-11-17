package com.mibess.notify.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.mibess.notify.channel.ChannelProvider;
import com.mibess.notify.channel.DeliveryAddress;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.Resource;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.stereotype.Component;

@Component
public class EmailSmtpProvider implements ChannelProvider {

  private final Json json;
  private final Crypto crypto;

  public EmailSmtpProvider(Json json, Crypto crypto) {
    this.json = json;
    this.crypto = crypto;
  }

  public String code() {
    return "EMAIL_SMTP";
  }

  private JsonNode credentials(Resource c) {
    return json.read(
      crypto.decrypt(
        c.spec().path("encryptedCredentials").asText(),
        c.workspaceId() + ":" + c.id()
      )
    );
  }

  public static void validateSpec(JsonNode s) {
    if (
      !s.path("smtpHost").asText().matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}") ||
      s.path("smtpPort").asInt() < 1 ||
      s.path("smtpPort").asInt() > 65535
    ) throw new IllegalArgumentException("Host ou porta SMTP inválidos.");
    if (
      !Set.of("TLS", "STARTTLS").contains(s.path("smtpSecurity").asText())
    ) throw new IllegalArgumentException("SMTP exige TLS ou STARTTLS.");
    DeliveryAddress.email(s.path("fromEmail").asText());
    if (!s.path("replyTo").asText().isBlank()) DeliveryAddress.email(
      s.path("replyTo").asText()
    );
    if (!s.path("fromName").asText().isBlank()) DeliveryAddress.header(
      s.path("fromName").asText(),
      "fromName",
      150
    );
  }

  public boolean validateConfiguration(Resource c) {
    try {
      validateSpec(c.spec());
      var credentials = credentials(c);
      return (
        !credentials.path("username").asText().isBlank() &&
        !credentials.path("password").asText().isBlank()
      );
    } catch (RuntimeException e) {
      return false;
    }
  }

  protected Session session(Resource c) {
    Properties p = new Properties();
    p.setProperty("mail.smtp.auth", "true");
    p.setProperty(
      "mail.smtp.ssl.enable",
      String.valueOf(c.spec().path("smtpSecurity").asText().equals("TLS"))
    );
    p.setProperty(
      "mail.smtp.starttls.enable",
      String.valueOf(c.spec().path("smtpSecurity").asText().equals("STARTTLS"))
    );
    p.setProperty(
      "mail.smtp.starttls.required",
      String.valueOf(c.spec().path("smtpSecurity").asText().equals("STARTTLS"))
    );
    p.setProperty("mail.smtp.ssl.checkserveridentity", "true");
    p.setProperty("mail.smtp.connectiontimeout", "10000");
    p.setProperty("mail.smtp.timeout", "20000");
    p.setProperty("mail.smtp.writetimeout", "20000");
    p.setProperty("mail.smtp.quitwait", "false");
    p.setProperty("mail.smtp.sendpartial", "false");
    return Session.getInstance(p);
  }

  protected Transport transport(Session session) throws MessagingException {
    return session.getTransport("smtp");
  }

  public Result send(SendCommand command) {
    var connection = command.connection();
    if (!validateConfiguration(connection)) throw new DeliveryFailure(
      "SMTP_NOT_CONFIGURED",
      false,
      false,
      0
    );
    var s = connection.spec();
    var data = command.message();
    final String recipient, subject;
    try {
      recipient = DeliveryAddress.email(command.recipient());
      subject = DeliveryAddress.header(
        data.path("subject").asText(),
        "subject",
        200
      );
      if (
        data.path("text").asText().isBlank() ||
        data.path("text").asText().length() > 20000 ||
        data.path("html").asText().length() > 50000
      ) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new DeliveryFailure("SMTP_INVALID_MESSAGE", false, false, 0);
    }
    Transport transport = null;
    boolean sending = false;
    try {
      Session session = session(connection);
      var message = new MimeMessage(session);
      String from = DeliveryAddress.email(
        data.path("fromAddress").asText(s.path("fromEmail").asText())
      );
      String fromName = data
        .path("fromName")
        .asText(s.path("fromName").asText());
      if (!fromName.isBlank()) DeliveryAddress.header(
        fromName,
        "fromName",
        150
      );
      message.setFrom(
        new InternetAddress(from, fromName, StandardCharsets.UTF_8.name())
      );
      message.setRecipient(
        Message.RecipientType.TO,
        new InternetAddress(recipient, true)
      );
      String replyTo = data.path("replyTo").asText(s.path("replyTo").asText());
      if (!replyTo.isBlank()) message.setReplyTo(new Address[] {
        new InternetAddress(DeliveryAddress.email(replyTo), true),
      });
      message.setSubject(subject, StandardCharsets.UTF_8.name());
      message.setSentDate(new Date());
      if (data.path("html").asText().isBlank()) message.setText(
        data.path("text").asText(),
        StandardCharsets.UTF_8.name()
      );
      else {
        MimeMultipart alternatives = new MimeMultipart("alternative");
        MimeBodyPart text = new MimeBodyPart();
        text.setText(data.path("text").asText(), StandardCharsets.UTF_8.name());
        alternatives.addBodyPart(text);
        MimeBodyPart html = new MimeBodyPart();
        html.setText(
          data.path("html").asText(),
          StandardCharsets.UTF_8.name(),
          "html"
        );
        alternatives.addBodyPart(html);
        message.setContent(alternatives);
      }
      message.saveChanges();
      transport = transport(session);
      var secrets = credentials(connection);
      transport.connect(
        s.path("smtpHost").asText(),
        s.path("smtpPort").asInt(),
        secrets.path("username").asText(),
        secrets.path("password").asText()
      );
      sending = true;
      transport.sendMessage(message, message.getAllRecipients());
      return new Result(message.getMessageID(), 250);
    } catch (AuthenticationFailedException e) {
      throw new DeliveryFailure(
        "SMTP_AUTHENTICATION_FAILED",
        false,
        false,
        535
      );
    } catch (SMTPSendFailedException e) {
      int code = e.getReturnCode();
      throw new DeliveryFailure(
        "SMTP_REJECTED_" + code,
        code >= 400 && code < 500,
        false,
        code
      );
    } catch (SMTPAddressFailedException e) {
      int code = e.getReturnCode();
      throw new DeliveryFailure(
        "SMTP_RECIPIENT_REJECTED_" + code,
        code >= 400 && code < 500,
        false,
        code
      );
    } catch (SendFailedException e) {
      Exception nested = e.getNextException();
      if (
        (e.getValidSentAddresses() == null ||
          e.getValidSentAddresses().length == 0) &&
        nested instanceof SMTPAddressFailedException rejected
      ) {
        int code = rejected.getReturnCode();
        throw new DeliveryFailure(
          "SMTP_RECIPIENT_REJECTED_" + code,
          code >= 400 && code < 500,
          false,
          code
        );
      }
      throw new DeliveryFailure("SMTP_DELIVERY_UNKNOWN", false, true, 0);
    } catch (Exception e) {
      // After DATA, a lost acknowledgement may follow acceptance. Never retry blindly.
      throw new DeliveryFailure(
        sending ? "SMTP_DELIVERY_UNKNOWN" : "SMTP_CONNECTION_FAILED",
        !sending,
        sending,
        0
      );
    } finally {
      if (transport != null) try {
        transport.close();
      } catch (MessagingException ignored) {
        /* Acceptance must survive a failed QUIT. */
      }
    }
  }
}
