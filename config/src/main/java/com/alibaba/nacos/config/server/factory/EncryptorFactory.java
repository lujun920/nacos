/*
 * Dian.so Inc.
 * Copyright (c) 2016-2019 All Rights Reserved.
 */
package com.alibaba.nacos.config.server.factory;

import com.alibaba.nacos.config.server.exception.KeyFormatException;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.MiscPEMGenerator;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.util.io.pem.PemObjectGenerator;
import org.bouncycastle.util.io.pem.PemWriter;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.rsa.crypto.RsaSecretEncryptor;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.regex.Pattern;

/**
 * TODO
 *
 * @author baizhang
 * @version: v 0.1 EncryptorFactory.java, 2019-06-19 22:07 Exp $
 */
public class EncryptorFactory {
    private static final Pattern NEWLINE_ESCAPE_PATTERN = Pattern.compile("\\r|\\n");
    private String salt = "deadbeef";

    private static final String RSA_PRIVATE_KEY="RSA PRIVATE KEY";
    private static final String RSA_PUBLIC_KEY="RSA PUBLIC KEY";
    private static final String SSH_RSA="ssh-rsa";


    public EncryptorFactory(String salt) {
        this.salt = salt;
    }

    public TextEncryptor create(String data) {
        TextEncryptor encryptor;
        if (data.contains(RSA_PRIVATE_KEY)) {
            try {
                String normalizedPemData = normalizePem(data);
                encryptor = new RsaSecretEncryptor(
                    NEWLINE_ESCAPE_PATTERN.matcher(normalizedPemData).replaceAll(""));
            } catch (IllegalArgumentException e) {
                throw new KeyFormatException(e);
            }
        } else if (data.startsWith(SSH_RSA) || data.contains(RSA_PUBLIC_KEY)) {
            throw new KeyFormatException();
        } else {
            encryptor = Encryptors.text(data, salt);
        }
        return encryptor;
    }

    private String normalizePem(String data) {
        PEMKeyPair pemKeyPair = null;
        try (PEMParser pemParser = new PEMParser(new StringReader(data))) {
            pemKeyPair = (PEMKeyPair) pemParser.readObject();
            PrivateKeyInfo privateKeyInfo = pemKeyPair.getPrivateKeyInfo();

            StringWriter textWriter = new StringWriter();
            try (PemWriter pemWriter = new PemWriter(textWriter)) {
                PemObjectGenerator pemObjectGenerator = new MiscPEMGenerator(
                    privateKeyInfo);

                pemWriter.writeObject(pemObjectGenerator);
                pemWriter.flush();
                return textWriter.toString();
            }
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
