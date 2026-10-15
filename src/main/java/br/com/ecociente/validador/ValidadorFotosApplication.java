package br.com.ecociente.validador;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** A autenticação é só por X-Api-Key (SegurancaConfig): sem usuário padrão do Spring Security. */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class ValidadorFotosApplication {

  public static void main(String[] args) {
    SpringApplication.run(ValidadorFotosApplication.class, args);
  }
}
