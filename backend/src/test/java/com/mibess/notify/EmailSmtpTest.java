package com.mibess.notify;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mibess.notify.channel.*;
import com.mibess.notify.channel.ChannelProvider.*;
import com.mibess.notify.email.EmailSmtpProvider;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.Resource;
import com.mibess.notify.template.TemplateRenderer;
import jakarta.mail.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;

class EmailSmtpTest {

  final Json json = new Json(new ObjectMapper());
  final Crypto crypto = new Crypto(
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
  );

  Resource connection(int port, String security) {
    UUID workspace = UUID.randomUUID(),
      id = UUID.randomUUID();
    var spec = json
      .object()
      .put("provider", "EMAIL_SMTP")
      .put("smtpHost", "localhost")
      .put("smtpPort", port)
      .put("smtpSecurity", security)
      .put("fromEmail", "contato@artgian.com.br")
      .put("fromName", "Artgian")
      .put("replyTo", "contato@artgian.com.br");
    spec.put(
      "encryptedCredentials",
      crypto.encrypt(
        "{\"username\":\"smtp-test\",\"password\":\"local-test-only\"}",
        workspace + ":" + id
      )
    );
    return new Resource(
      id,
      workspace,
      "SMTP_TEST",
      "Artgian SMTP",
      true,
      spec,
      "",
      ""
    );
  }

  SendCommand command(Resource c) {
    return new SendCommand(
      "buyer@example.com",
      json
        .object()
        .put("subject", "Compra confirmada")
        .put("text", "Seu pedido foi pago.")
        .put("html", "<p>Seu pedido foi pago.</p>"),
      c
    );
  }

  @Test
  void rejectsMultipleMailboxesHeaderInjectionAndUnencryptedSmtp() {
    assertThat(DeliveryAddress.email(" BUYER@EXAMPLE.COM ")).isEqualTo(
      "buyer@example.com"
    );
    for (String invalid : List.of(
      "bad",
      "a@example.com,b@example.com",
      "Name <a@example.com>",
      "a@example.com\r\nBcc: x@example.com",
      "a@example.com\n"
    ))
      assertThatThrownBy(() -> DeliveryAddress.email(invalid)).isInstanceOf(
        IllegalArgumentException.class
      );
    assertThat(
      new EmailSmtpProvider(json, crypto).validateConfiguration(
        connection(25, "NONE")
      )
    ).isFalse();
    var c = command(connection(465, "TLS"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) c.message()).put(
      "subject",
      "Order\r\nBcc: x@example.com"
    );
    assertThatThrownBy(() -> new EmailSmtpProvider(json, crypto).send(c))
      .isInstanceOf(DeliveryFailure.class)
      .satisfies(e ->
        assertThat(((DeliveryFailure) e).code).isEqualTo("SMTP_INVALID_MESSAGE")
      );
  }

  @Test
  void htmlVariablesAreEscapedAndPlainTextPreserved() {
    var spec = json
      .object()
      .put("body", "Olá {{name}}")
      .put("subject", "Pedido {{name}}")
      .put("html", "<b>{{name}}</b>");
    spec.set("variables", json.tree(List.of("name")));
    var event = json.read("{\"data\":{\"name\":\"<script> & \\\"x\\\"\"}}");
    assertThat(TemplateRenderer.render(spec, event)).contains("<script>");
    assertThat(
      TemplateRenderer.renderField(spec, event, "html", true)
    ).isEqualTo("<b>&lt;script&gt; &amp; &quot;x&quot;</b>");
  }

  @Test
  void temporaryRejectionRetriesButLostAcknowledgementDoesNot()
    throws Exception {
    Transport transport = mock(Transport.class);
    var provider = new EmailSmtpProvider(json, crypto) {
      @Override
      protected Transport transport(Session s) {
        return transport;
      }
    };
    doThrow(
      new SMTPSendFailedException(
        "DATA",
        451,
        "temporary",
        null,
        null,
        null,
        null
      )
    )
      .when(transport)
      .sendMessage(any(), any());
    assertThatThrownBy(() -> provider.send(command(connection(465, "TLS"))))
      .isInstanceOf(DeliveryFailure.class)
      .satisfies(e -> {
        assertThat(((DeliveryFailure) e).retryable).isTrue();
        assertThat(((DeliveryFailure) e).ambiguous).isFalse();
      });
    doThrow(new MessagingException("lost response"))
      .when(transport)
      .sendMessage(any(), any());
    assertThatThrownBy(() -> provider.send(command(connection(465, "TLS"))))
      .isInstanceOf(DeliveryFailure.class)
      .satisfies(e -> {
        assertThat(((DeliveryFailure) e).retryable).isFalse();
        assertThat(((DeliveryFailure) e).ambiguous).isTrue();
      });
  }

  @Test
  void starttlsIsRequiredBeforeCredentialsOrMessageAreSent() throws Exception {
    List<String> commands = new CopyOnWriteArrayList<>();
    try (
      ServerSocket server = new ServerSocket(
        0,
        1,
        InetAddress.getLoopbackAddress()
      );
      var executor = Executors.newSingleThreadExecutor()
    ) {
      Future<?> serverTask = executor.submit(() -> {
        try (Socket socket = server.accept()) {
          socket.setSoTimeout(5000);
          var reader = new BufferedReader(
            new InputStreamReader(
              socket.getInputStream(),
              StandardCharsets.US_ASCII
            )
          );
          var writer = new PrintWriter(
            socket.getOutputStream(),
            true,
            StandardCharsets.US_ASCII
          );
          writer.print("220 localhost SMTP\r\n");
          writer.flush();
          String line;
          while ((line = reader.readLine()) != null) {
            commands.add(line);
            if (line.startsWith("EHLO")) {
              writer.print("250 localhost\r\n");
              writer.flush();
            } else break;
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
      assertThatThrownBy(() ->
        new EmailSmtpProvider(json, crypto).send(
          command(connection(server.getLocalPort(), "STARTTLS"))
        )
      ).isInstanceOf(DeliveryFailure.class);
      serverTask.get(10, TimeUnit.SECONDS);
      assertThat(commands).noneMatch(
        s ->
          s.startsWith("AUTH") || s.startsWith("MAIL") || s.startsWith("DATA")
      );
    }
  }

  @Test
  void sendsMimeOverRealLocalTlsSmtpAndDoesNotTreatQuitFailureAsUnsent()
    throws Exception {
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (InputStream input = getClass().getResourceAsStream("/smtp-test-keystore.fixture")) {
      keys.load(input, "test-only".toCharArray());
    }
    var km = KeyManagerFactory.getInstance(
      KeyManagerFactory.getDefaultAlgorithm()
    );
    km.init(keys, "test-only".toCharArray());
    var tm = TrustManagerFactory.getInstance(
      TrustManagerFactory.getDefaultAlgorithm()
    );
    tm.init(keys);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    List<String> commands = new CopyOnWriteArrayList<>();
    StringBuilder body = new StringBuilder();
    try (
      SSLServerSocket server = (SSLServerSocket) context
        .getServerSocketFactory()
        .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
      var executor = Executors.newSingleThreadExecutor()
    ) {
      Future<?> serverTask = executor.submit(() -> {
        try (Socket socket = server.accept()) {
          socket.setSoTimeout(5000);
          var reader = new BufferedReader(
            new InputStreamReader(
              socket.getInputStream(),
              StandardCharsets.UTF_8
            )
          );
          var writer = new PrintWriter(
            socket.getOutputStream(),
            true,
            StandardCharsets.US_ASCII
          );
          writer.print("220 localhost SMTP\r\n");
          writer.flush();
          String line;
          boolean data = false;
          while ((line = reader.readLine()) != null) {
            if (data) {
              if (line.equals(".")) {
                data = false;
                writer.print("250 accepted\r\n");
                writer.flush();
              } else body.append(line).append("\n");
              continue;
            }
            commands.add(line);
            if (line.startsWith("EHLO")) writer.print(
              "250-localhost\r\n250 AUTH PLAIN\r\n"
            );
            else if (line.startsWith("AUTH")) writer.print(
              "235 authenticated\r\n"
            );
            else if (line.equals("DATA")) {
              writer.print("354 continue\r\n");
              data = true;
            } else if (line.equals("QUIT")) break;
            else writer.print("250 ok\r\n");
            writer.flush();
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
      var provider = new EmailSmtpProvider(json, crypto) {
        @Override
        protected Session session(Resource c) {
          Session s = super.session(c);
          s.getProperties().put(
            "mail.smtp.ssl.socketFactory",
            context.getSocketFactory()
          );
          return s;
        }
      };
      Result result = provider.send(
        command(connection(server.getLocalPort(), "TLS"))
      );
      serverTask.get(10, TimeUnit.SECONDS);
      assertThat(result.messageId()).isNotBlank();
      assertThat(result.responseCode()).isEqualTo(250);
      assertThat(commands).contains(
        "MAIL FROM:<contato@artgian.com.br>",
        "RCPT TO:<buyer@example.com>",
        "DATA"
      );
      assertThat(body.toString()).contains(
        "multipart/alternative",
        "text/plain",
        "text/html",
        "Reply-To: contato@artgian.com.br"
      );
    }
  }
}
