package com.jmxlogger.support;

import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link BuildInfo} 的解析与降级测试：git.properties 是构建产物，
 * 测试不能直接依赖它的内容，因此用 {@link BuildInfo#from(Properties)} 喂样本。
 */
public class BuildInfoTest {

    private static Properties gitProperties() {
        Properties props = new Properties();
        props.setProperty("git.build.version", "1.0.0");
        props.setProperty("git.commit.id.abbrev", "cb6fe8ed");
        props.setProperty("git.commit.id.describe", "v1.0.0-21-gcb6fe8ed");
        props.setProperty("git.commit.time", "20260919");
        props.setProperty("git.build.time", "20260919");
        props.setProperty("git.dirty", "false");
        return props;
    }

    @Test
    public void prefersGitDescribeWithCommitTime() {
        BuildInfo info = BuildInfo.from(gitProperties());

        assertTrue(info.isKnown());
        assertEquals("v1.0.0-21-gcb6fe8ed", info.gitDescribe());
        assertEquals("jmx-logger v1.0.0-21-gcb6fe8ed (20260919)", info.versionLine());
    }

    @Test
    public void keepsDirtyMarkerFromGitDescribe() {
        Properties props = gitProperties();
        props.setProperty("git.commit.id.describe", "v1.0.0-21-gcb6fe8ed+");
        props.setProperty("git.dirty", "true");

        BuildInfo info = BuildInfo.from(props);

        assertTrue(info.isDirty());
        // describe 结尾的 + 由 gitDescribe 的 dirty 标记给出，输出里必须原样保留
        assertEquals("jmx-logger v1.0.0-21-gcb6fe8ed+ (20260919)", info.versionLine());
    }

    /** 浅克隆 / 仓库无 tag 时插件可能给不出 describe：退化为 pom 版本号。 */
    @Test
    public void fallsBackToProjectVersionWithoutDescribe() {
        Properties props = gitProperties();
        props.remove("git.commit.id.describe");

        BuildInfo info = BuildInfo.from(props);

        assertTrue(info.isKnown());
        assertEquals("1.0.0", info.version());
        assertEquals("jmx-logger 1.0.0", info.versionLine());
    }

    @Test
    public void fallsBackToUnknownWhenPropertiesAreEmpty() {
        BuildInfo info = BuildInfo.from(new Properties());

        assertFalse(info.isKnown());
        assertEquals(BuildInfo.UNKNOWN, info.version());
        assertEquals(BuildInfo.UNKNOWN, info.gitDescribe());
        assertTrue("没有构建信息时也要给出可读输出，实际: " + info.versionLine(),
                info.versionLine().contains("构建信息不可用"));
    }

    /**
     * 没有 .git 的构建（源码包、CI 归档）不会生成 git.properties，
     * 这种 jar 的 -V 也必须能正常退出。
     */
    @Test
    public void loadNeverThrows() {
        BuildInfo info = BuildInfo.load();

        assertTrue("versionLine() 不能为 null/空，实际: " + info.versionLine(),
                info.versionLine() != null && !info.versionLine().isEmpty());
    }
}
