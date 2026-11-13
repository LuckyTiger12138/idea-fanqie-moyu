package com.tomato.reader.api;

/** 接口层统一异常，message 直接用中文，可直接展示给用户。 */
public class ApiException extends Exception {

    public ApiException(String message) {
        super(message);
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
