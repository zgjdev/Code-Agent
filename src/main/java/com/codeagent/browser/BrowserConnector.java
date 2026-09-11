package com.codeagent.browser;

public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}
