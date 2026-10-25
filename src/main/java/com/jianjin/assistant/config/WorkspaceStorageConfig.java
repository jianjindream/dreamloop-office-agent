package com.jianjin.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Product records never silently fall back to volatile memory. */
@Component
@ConfigurationProperties(prefix = "app.storage")
public class WorkspaceStorageConfig {
    private String backend = "h2";
    private String directory = "data/workspace";

    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public String getDirectory() { return directory; }
    public void setDirectory(String directory) { this.directory = directory; }
}
