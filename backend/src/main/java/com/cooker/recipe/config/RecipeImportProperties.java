package com.cooker.recipe.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 显式初始化入口的配置，datasetPath 没有默认值以阻止隐式本机导入。 */
@ConfigurationProperties(prefix = "cooker.recipe-import")
public class RecipeImportProperties {
    private boolean enabled;
    private Path datasetPath;
    private String sourceVersion;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Path getDatasetPath() { return datasetPath; }
    public void setDatasetPath(Path datasetPath) { this.datasetPath = datasetPath; }
    public String getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(String sourceVersion) { this.sourceVersion = sourceVersion; }
}
