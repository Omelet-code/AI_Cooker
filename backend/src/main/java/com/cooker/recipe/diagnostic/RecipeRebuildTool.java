package com.cooker.recipe.diagnostic;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.cooker.recipe.mapper.*;
import com.cooker.recipe.parser.HowToCookMarkdownParser;
import com.cooker.recipe.service.RecipeImportFileService;
import com.cooker.recipe.service.RecipeImportService;
import com.cooker.recipe.service.impl.RecipeImportFileServiceImpl;
import com.cooker.recipe.service.impl.RecipeImportServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** D1-R独立显式入口：只迁移指定新库并复用真实导入Service，不启动Boot/Web/模型。 */
public final class RecipeRebuildTool {
    public static final String DATABASE = "cooker_rebuild_d1r2_20261007";
    public static final String SOURCE_VERSION = "1.6.0-a0b14bd";
    private RecipeRebuildTool() { }

    public static void main(String[] args) {
        try { execute(args); }
        catch (Exception exception) {
            // 不输出连接/凭证/驱动堆栈；失败后保留新库现场，入口没有删库或自动重建能力。
            System.err.println(exception instanceof IllegalArgumentException ? exception.getMessage()
                    : "新库迁移或导入失败；现场已保留，请检查专属运行记录，禁止删库重试");
            System.exit(1);
        }
    }

    public static void execute(String[] args) throws Exception {
        require(args.length == 6 && args[0].equals("--target") && args[2].equals("--dataset-path")
                && args[4].equals("--source-version"), "必须显式指定--target、--dataset-path和--source-version");
        String target = args[1]; validateTarget(target);
        require(SOURCE_VERSION.equals(args[5]), "来源版本必须沿用真实固定快照版本，不得伪造版本绕过成功跳过");
        Path dataset = Path.of(args[3]); require(Files.isDirectory(dataset), "只读数据源目录不存在");
        require(target.equals(System.getenv("COOKER_REBUILD_JDBC_URL")), "显式目标与专用进程环境不一致");
        String user = System.getenv("COOKER_REBUILD_USER"), password = System.getenv("COOKER_REBUILD_PASSWORD");
        require("root".equals(user) && password != null && !password.isEmpty(), "准确root角色和数据库凭证须由专用进程环境注入");
        var source = new DriverManagerDataSource(target,user,password);
        try (var connection = source.getConnection()) {
            require(DATABASE.equals(connection.getCatalog()) && target.equals(connection.getMetaData().getURL())
                    && "root".equals(connection.getMetaData().getUserName()), "实际连接库/地址/角色与本次授权目标不一致");
        }
        // 数据库由授权脚本在备份验证后单独创建；Flyway只连接这个新库。
        var migrated = Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        require(new JdbcTemplate(source).queryForObject("SELECT count(*) FROM app_user",Long.class) == 0,
                "新库已有账号，超出本次不迁移测试用户的边界，保留现场并停止");
        try (var context = importContext(source)) {
            var report = context.getBean(RecipeImportService.class).importHowToCookSnapshot(dataset,SOURCE_VERSION);
            var result = new ObjectMapper().createObjectNode(); result.put("database",DATABASE);
            result.put("parserRule",HowToCookMarkdownParser.RULE_VERSION);
            result.put("migrationsExecuted",migrated.migrationsExecuted); result.set("importReport",new ObjectMapper().valueToTree(report));
            System.out.println("D1R_RESULT=" + result);
            require(report.failedCount() == 0, "导入有失败路径，已输出报告；保留新库现场，不删除覆盖或自动换版本重试");
        }
    }

    /** 连接前拒绝旧库、其他实例、模糊端口及含凭证/参数的URL。 */
    public static void validateTarget(String target) {
        require(target != null && target.startsWith("jdbc:postgresql://"), "目标必须是明确PostgreSQL JDBC地址");
        URI uri;
        // URI异常可能带回原URL；转为固定中文错误，避免非法地址中的凭证出现在诊断中。
        try { uri = URI.create(target.substring(5)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("目标地址格式非法，已拒绝连接"); }
        require(uri.getHost() != null && List.of("localhost","127.0.0.1").contains(uri.getHost()) && uri.getPort() == 5433
                && ("/"+DATABASE).equals(uri.getPath()) && uri.getUserInfo() == null
                && uri.getQuery() == null && uri.getFragment() == null, "本次只允许localhost/127.0.0.1:5433指定新库，不得连接旧库或其他目标");
    }

    /** 只装配既有批次/单文件Service和十个导入Mapper；真实事务代理与测试共用。 */
    public static AnnotationConfigApplicationContext importContext(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true); configuration.setDefaultStatementTimeout(30);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setConfiguration(configuration); factory.setTransactionFactory(new SpringManagedTransactionFactory());
        var sessions = factory.getObject();
        var session = new SqlSessionTemplate(sessions);
        var context = new AnnotationConfigApplicationContext();
        try {
            context.register(ImportTransactions.class);
            context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
            registerMapper(context,session,RecipeImportBatchMapper.class);
            registerMapper(context,session,RecipeSourceMapper.class);
            registerMapper(context,session,RecipeSourceDocumentMapper.class);
            registerMapper(context,session,RecipeMapper.class);
            registerMapper(context,session,IngredientMapper.class);
            registerMapper(context,session,IngredientAliasMapper.class);
            registerMapper(context,session,RecipeIngredientMapper.class);
            registerMapper(context,session,CookingToolMapper.class);
            registerMapper(context,session,RecipeToolMapper.class);
            registerMapper(context,session,RecipeStepMapper.class);
            context.registerBean(HowToCookMarkdownParser.class);
            context.registerBean(RecipeImportFileServiceImpl.class);
            context.registerBean(RecipeImportServiceImpl.class);
            context.refresh();
            require(AopUtils.isAopProxy(context.getBean(RecipeImportFileService.class)), "单文件导入事务代理未建立，禁止导入");
            return context;
        } catch (Exception exception) { context.close(); throw exception; }
    }

    private static <T> void registerMapper(AnnotationConfigApplicationContext context,SqlSessionTemplate session,Class<T> type) {
        session.getConfiguration().addMapper(type);
        context.registerBean(type, () -> session.getMapper(type));
    }

    // 无@Component/@Configuration，普通应用扫描不会注册；仅由上方独立上下文显式加载。
    @EnableTransactionManagement
    static class ImportTransactions { }
    private static void require(boolean valid,String message) { if (!valid) throw new IllegalArgumentException(message); }
}
