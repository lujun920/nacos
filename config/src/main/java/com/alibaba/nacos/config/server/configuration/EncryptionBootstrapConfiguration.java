/*
 * Dian.so Inc.
 * Copyright (c) 2016-2019 All Rights Reserved.
 */
package com.alibaba.nacos.config.server.configuration;

import com.alibaba.nacos.config.server.factory.EncryptorFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.rsa.crypto.KeyStoreKeyFactory;
import org.springframework.security.rsa.crypto.RsaSecretEncryptor;
import org.springframework.util.StringUtils;

/**
 * TODO
 *
 * @author baizhang
 * @version: v 0.1 EncryptionBootstrapConfiguration.java, 2019-06-19 21:51 Exp $
 */
@Configuration
@ConditionalOnClass({TextEncryptor.class})
@EnableConfigurationProperties({KeyProperties.class})
public class EncryptionBootstrapConfiguration {

    @Configuration
    @Conditional(KeyCondition.class)
    @ConditionalOnClass(RsaSecretEncryptor.class)
    @EnableConfigurationProperties({RsaProperties.class})
    protected static class RsaEncryptionConfiguration {

        @Autowired
        private KeyProperties key;

        @Autowired
        private RsaProperties rsaProperties;

        @Bean
        @ConditionalOnMissingBean(TextEncryptor.class)
        public TextEncryptor textEncryptor() {
            KeyProperties.KeyStore keyStore = this.key.getKeyStore();
            if (keyStore.getLocation() != null) {
                if (keyStore.getLocation().exists()) {
                    return new RsaSecretEncryptor(
                        new KeyStoreKeyFactory(keyStore.getLocation(),
                            keyStore.getPassword().toCharArray()).getKeyPair(
                            keyStore.getAlias(),
                            keyStore.getSecret().toCharArray()),
                        this.rsaProperties.getAlgorithm(), this.rsaProperties.getSalt(),
                        this.rsaProperties.isStrong());
                }

                throw new IllegalStateException("Invalid keystore location");
            }

            return new EncryptorFactory(this.key.getSalt()).create(this.key.getKey());
        }

    }

    public static class KeyCondition extends SpringBootCondition {

        private static final String STORE_LOCATION= "encrypt.key-store.location";
        private static final String STORE_PASSWORD= "encrypt.key-store.password";
        private static final String ENCRYPT_KEY= "encrypt.key";

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context,
                                                AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            if (hasProperty(environment, STORE_LOCATION)) {
                if (hasProperty(environment, STORE_PASSWORD)) {
                    return ConditionOutcome.match("Keystore found in Environment");
                }
                return ConditionOutcome
                    .noMatch("Keystore found but no password in Environment");
            } else if (hasProperty(environment, ENCRYPT_KEY)) {
                return ConditionOutcome.match("Key found in Environment");
            }
            return ConditionOutcome.noMatch("Keystore nor key found in Environment");
        }

        private boolean hasProperty(Environment environment, String key) {
            String value = environment.getProperty(key);
            if (value == null) {
                return false;
            }
            return StringUtils.hasText(environment.resolvePlaceholders(value));
        }

    }
}
