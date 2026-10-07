package com.github.feng111627.resilientapiintegrationjava.stub;

import com.github.feng111627.resilientapiintegrationjava.client.UpstreamBaseUrlProvider;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;

/** 测试用：把上游地址指向本进程随机端口上的桩。端口在首次调用时解析。 */
public class LocalhostUpstreamBaseUrlProvider implements UpstreamBaseUrlProvider {

    private final ApplicationContext context;

    public LocalhostUpstreamBaseUrlProvider(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public String get() {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return "http://localhost:" + port;
    }
}
