import org.flywaydb.core.Flyway;

/** 专用隔离迁移入口；不启动Cooker，不读取环境或模型配置。 */
public final class FlywayPrepare {
    public static void main(String[] args) {
        if(args.length!=2 || !args[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/d2a_[a-z]+"))
            throw new IllegalArgumentException("仅允许D2-A临时PG地址");
        var result=Flyway.configure().dataSource(args[0],"root","fake-d2a-password")
            .locations("filesystem:"+args[1]).load().migrate();
        if(result.migrationsExecuted!=15)throw new IllegalStateException("必须实际执行当前V1～V15迁移");
        System.out.println("D2-A临时Flyway迁移通过：15");
    }
}
