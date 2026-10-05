package com.github.cuixudong74.resourcecostforecastjava;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;

/** 冒烟：Spring 上下文可装配全部组件。 */
@SpringBootTest(properties = "journal.dir=${java.io.tmpdir}/rcf-smoke-journal")
class ApplicationContextTest {

    @Test
    void contextLoads() {
    }
}
