/*
 * Dian.so Inc.
 * Copyright (c) 2016-2019 All Rights Reserved.
 */
package com.alibaba.nacos.config.server.controller;

import com.alibaba.nacos.config.server.constant.Constants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.codec.Base64;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * TODO
 *
 * @author baizhang
 * @version: v 0.1 ConfigSecurityController.java, 2019-06-27 15:52 Exp $
 */
@RestController
public class ConfigSecurityController {
    @PostMapping(value = Constants.ENCRYPT_CONTROLLER_PATH)
    public String encrypt(@RequestBody String data, @RequestHeader("Content-Type") MediaType type) {
        String input = stripFormData(data, type, false);
        return textEncryptor.encrypt(input);
    }

    @PostMapping(value = Constants.DECRYPT_CONTROLLER_PATH)
    public String decrypt(@RequestBody String data, @RequestHeader("Content-Type") MediaType type) {
        String input = stripFormData(data, type, true);
        return textEncryptor.decrypt(input);
    }

    private String stripFormData(String data, MediaType type, boolean cipher) {
        if (data.endsWith(Constants.EQUAL) && !type.equals(MediaType.TEXT_PLAIN)) {
            try {
                data = URLDecoder.decode(data, Constants.ENCODE);
                if (cipher) {
                    data = data.replace(Constants.BLANK, Constants.PLUS);
                }
            } catch (UnsupportedEncodingException e) {
                // Really?
            }
            String candidate = data.substring(0, data.length() - 1);
            if (cipher) {
                if (data.endsWith(Constants.EQUAL)) {
                    int nexValue= 2;
                    if (data.length() / nexValue != (data.length() + 1) / nexValue) {
                        try {
                            Hex.decode(candidate);
                            return candidate;
                        } catch (IllegalArgumentException e) {
                            if (Base64.isBase64(data.getBytes())) {
                                return data;
                            }
                        }
                    }
                }
                return data;
            }
            // User posted data with content type form but meant it to be text/plain
            data = candidate;
        }
        return data;
    }

    @Autowired
    private TextEncryptor textEncryptor;

}
