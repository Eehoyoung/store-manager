package com.storemanager.api.sysauth;

import java.util.Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * {@code app.mail.*} 값으로 SMTP 발신기를 직접 구성한다.
 *
 * <p>★ Spring Boot 표준 {@code spring.mail.*} 자동설정을 쓰지 않는다 — 이미 {@code app.*} 아래
 * 모든 설정을 모아 두는 관례(business·alimtalk 등)를 따르고, 값이 두 군데(spring.mail / app.mail)로
 * 갈라지는 것을 막기 위함이다.
 */
@Configuration
public class MailConfig {

    @Bean
    public JavaMailSender javaMailSender(MailProperties props) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(props.getHost());
        sender.setPort(props.getPort());
        sender.setUsername(props.getUsername());
        sender.setPassword(props.getPassword());
        sender.setDefaultEncoding("UTF-8");
        Properties mailProps = sender.getJavaMailProperties();
        mailProps.put("mail.transport.protocol", "smtp");
        mailProps.put("mail.smtp.auth", "true");
        mailProps.put("mail.smtp.starttls.enable", "true");
        return sender;
    }
}
