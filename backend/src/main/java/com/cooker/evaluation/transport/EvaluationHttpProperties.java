package com.cooker.evaluation.transport;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 默认关闭；只能由服务端启动参数绑定批准文件，没有网页管理或切换入口。 */
@ConfigurationProperties("cooker.evaluation.http")
public class EvaluationHttpProperties {
    private boolean enabled;
    private String batchFile;
    public boolean isEnabled(){return enabled;}
    public void setEnabled(boolean enabled){this.enabled=enabled;}
    public String getBatchFile(){return batchFile;}
    public void setBatchFile(String batchFile){this.batchFile=batchFile;}
}
