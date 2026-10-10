package com.cooker.recipe.diagnostic;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.cooker.recipe.dto.repair.*;
import com.cooker.recipe.mapper.RecipeRepairMapper;
import com.cooker.recipe.service.RecipeRepairService;
import com.cooker.recipe.service.impl.RecipeRepairServiceImpl;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.*;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.*;
import static com.cooker.recipe.dto.repair.RecipeRepairEvidence.*;

/** 独立显式入口；不启动SpringBoot，不迁移、导入或调用模型。 */
public final class RecipeRepairTool {
    private RecipeRepairTool() { }
    public static void main(String[] arguments) {
        try { execute(arguments); }
        catch (Exception e) {
            // 不输出连接字符串、用户名/密码或驱动堆栈。业务拒绝理由为项目固定中文文案。
            System.err.println(e instanceof IllegalArgumentException ? e.getMessage() : "治理执行失败，事务未提交；请检查审批、目标环境及隔离测试证据");
            System.exit(1);
        }
    }
    public static void execute(String[] arguments) throws Exception {
        require(arguments.length > 0, "请指定review、seal-approval或治理参数；默认治理模式dry-run");
        List<String> args = new ArrayList<>(Arrays.asList(arguments));
        String mode = Set.of("review","seal-approval","dry-run","apply","rollback").contains(args.getFirst()) ? args.removeFirst() : "dry-run";
        if (mode.equals("review")) {
            require(args.size() == 3, "review需固定输入、提案与输出目录");
            var evidence = RecipeRepairEvidence.load(Path.of(args.get(0)),Path.of(args.get(1)));
            RecipeRepairReview.write(evidence,output(Path.of(args.get(2)))); System.out.println("逐ID审定包生成；批准0，数据库应用0"); return;
        }
        if (mode.equals("seal-approval")) {
            require(args.size() == 2, "seal-approval需人工编辑文件与独立输出文件");
            require(Files.size(Path.of(args.get(0))) <= 4_194_304, "审批文件超过4MiB上限");
            var approval = JSON.readValue(Files.readString(Path.of(args.get(0))), RecipeRepairApproval.class);
            var node = (com.fasterxml.jackson.databind.node.ObjectNode)JSON.valueToTree(approval);
            node.put("approvalHash", approvalHash(approval)); Path target = output(Path.of(args.get(1)));
            Files.createDirectories(target.getParent()); Files.writeString(target,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node),StandardCharsets.UTF_8);
            System.out.println("人工决定已绑定内容hash；此操作不构成用户实际批准，不连接数据库"); return;
        }
        require(args.size() == 5 && args.get(3).equals("--target"), "治理需固定输入、提案、审批文件和--target精确JDBC目标");
        var evidence = RecipeRepairEvidence.load(Path.of(args.get(0)),Path.of(args.get(1)));
        require(Files.size(Path.of(args.get(2))) <= 4_194_304, "审批文件超过4MiB上限");
        var approval = JSON.readValue(Files.readString(Path.of(args.get(2))),RecipeRepairApproval.class);
        evidence.validate(approval); // 未批准/被篡改文件在创建任何数据库连接前拒绝。
        String url = System.getenv("COOKER_REPAIR_JDBC_URL"); require(url != null && url.equals(args.get(4)) && url.equals(approval.targetIdentifier()), "--target与专用环境目标不一致");
        require(url.startsWith("jdbc:postgresql://"), "目标必须是明确PostgreSQL JDBC地址");
        URI uri = URI.create(url.substring(5));
        require(uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null && uri.getHost() != null
                && uri.getPort() > 0 && uri.getPath().equals("/" + approval.targetDatabase()), "目标地址必须明确主机端口/数据库且不含凭证或参数");
        String user = System.getenv("COOKER_REPAIR_USER"), password = System.getenv("COOKER_REPAIR_PASSWORD");
        require(user != null && password != null, "治理凭证只能从专用环境注入");
        var dataSource = new DriverManagerDataSource(url,user,password);
        RecipeRepairService service = service(dataSource);
        RecipeRepairResult result = switch(mode) { case "apply" -> service.apply(evidence,approval); case "rollback" -> service.rollback(evidence,approval); default -> service.dryRun(evidence,approval); };
        System.out.println(JSON.writeValueAsString(result));
    }
    /** 与临时PG测试复用真实Mapper/事务配置，创建本身不迁移或初始化任何业务表。 */
    public static RecipeRepairService service(DataSource source) throws Exception {
        MybatisConfiguration config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setDefaultStatementTimeout(20); config.addMapper(RecipeRepairMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var transactions = new DataSourceTransactionManager(source); transactions.setDefaultTimeout(30);
        return new RecipeRepairServiceImpl(session.getMapper(RecipeRepairMapper.class),transactions);
    }
    private static Path output(Path path) {
        Path out=path.toAbsolutePath().normalize();require(out.startsWith(Path.of(".data/d1-b").toAbsolutePath().normalize()),"输出只能在当前.data/d1-b，不能覆盖D1-A证据");return out;
    }
}
