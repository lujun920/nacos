/*
 * Dian.so Inc.
 * Copyright (c) 2016-2019 All Rights Reserved.
 */
package com.alibaba.nacos.config.server.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.rsa.crypto.RsaAlgorithm;

/**
 * TODO
 *
 * @author baizhang
 * @version: v 0.1 RsaProperties.java, 2019-06-19 21:43 Exp $
 */
@ConditionalOnClass(RsaAlgorithm.class)
@ConfigurationProperties("encrypt.rsa")
public class RsaProperties {
    /**
     * The RSA algorithm to use (DEFAULT or OEAP). Once it is set, do not change it (or
     * existing ciphers will not be decryptable).
     */
    private RsaAlgorithm algorithm = RsaAlgorithm.DEFAULT;

    /**
     * Flag to indicate that "strong" AES encryption should be used internally. If
     * true, then the GCM algorithm is applied to the AES encrypted bytes. Default is
     * false (in which case "standard" CBC is used instead). Once it is set, do not
     * change it (or existing ciphers will not be decryptable).
     */
    private boolean strong = false;

    /**
     * Salt for the random secret used to encrypt cipher text. Once it is set, do not
     * change it (or existing ciphers will not be decryptable).
     */
    private String salt = "deadbeef";

    public RsaAlgorithm getAlgorithm() {
        return this.algorithm;
    }

    public void setAlgorithm(RsaAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public boolean isStrong() {
        return this.strong;
    }

    public void setStrong(boolean strong) {
        this.strong = strong;
    }

    public String getSalt() {
        return this.salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }
}
