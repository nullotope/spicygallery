package io.github.nullotope.spicygallery.server.web;

import io.github.nullotope.spicygallery.server.importjob.SystemInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Settings-page data (spec §8/§9): libraries, tool detection, config echo. */
@RestController
public class SystemController {

    private final SystemInfoService systemInfo;

    public SystemController(SystemInfoService systemInfo) {
        this.systemInfo = systemInfo;
    }

    @GetMapping("/api/system/info")
    public SystemInfoService.SystemInfo info() {
        return systemInfo.info();
    }
}
