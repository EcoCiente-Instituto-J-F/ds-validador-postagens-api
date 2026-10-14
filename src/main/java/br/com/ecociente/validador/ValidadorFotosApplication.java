package br.com.ecociente.validador;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ValidadorFotosApplication {

  public static void main(String[] args) {
    SpringApplication.run(ValidadorFotosApplication.class, args);
  }
}
