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

    private static Properties gitProperties(String version, String commit, String buildTime, String dirty) {
        Properties props = new Properties();
        props.setProperty("git.build.version", version);
        props.setProperty("git.commit.id.abbrev", commit);
        props.setProperty("git.commit.time", "20260919");
        props.setProperty("git.build.time", buildTime);
        props.setProperty("git.dirty", dirty);
        return props;
    }

    @Test
    public void describesVersionCommitAndBuildTime() {
        BuildInfo info = BuildInfo.from(gitProperties("1.0.0", "a03d520c", "20260919", "false"));

        assertTrue(info.isKnown());
        assertEquals("1.0.0", info.version());
        assertEquals("a03d520c", info.commitId());
        assertEquals("20260919", info.buildTime());
        assertEquals("jmx-logger 1.0.0 (commit a03d520c, 构建于 20260919)", info.describe());
    }

    @Test
    public void marksDirtyBuildWithPlusSuffix() {
        BuildInfo info = BuildInfo.from(gitProperties("1.0.0", "a03d520c", "20260919", "true"));

        assertTrue(info.isDirty());
        // 与 git describe 的约定一致：+ 表示构建时工作区有未提交改动
        assertEquals("a03d520c+", info.commitId());
        assertTrue(info.describe().contains("a03d520c+"));
    }

    @Test
    public void fallsBackToUnknownWhenPropertiesAreEmpty() {
        BuildInfo info = BuildInfo.from(new Properties());

        assertFalse(info.isKnown());
        assertEquals(BuildInfo.UNKNOWN, info.version());
        assertEquals(BuildInfo.UNKNOWN, info.commitId());
        assertTrue("没有构建信息时也要给出可读输出，实际: " + info.describe(),
                info.describe().contains("构建信息不可用"));
    }

    /**
     * 没有 .git 的构建（源码包、CI 归档）不会生成 git.properties，
     * 这种 jar 的 -V 也必须能正常退出。
     */
    @Test
    public void loadNeverThrows() {
        BuildInfo info = BuildInfo.load();

        assertTrue("describe() 不能为 null/空，实际: " + info.describe(),
                info.describe() != null && !info.describe().isEmpty());
    }
}
