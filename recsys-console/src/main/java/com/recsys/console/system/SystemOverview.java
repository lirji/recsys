package com.recsys.console.system;

import java.util.List;

public record SystemOverview(
        String projectName,
        String description,
        String stack,
        List<ModuleInfo> modules,
        List<SystemLink> links,
        List<ApiEndpoint> apis,
        List<CommandGroup> commands
) {
    public record ModuleInfo(
            String name,
            String type,
            String description,
            Integer port,
            Integer grpcPort,
            List<String> gatewayPrefixes,
            List<String> dependencies,
            boolean runnable,
            String frontendPath
    ) {
    }

    public record SystemLink(
            String name,
            String description,
            List<String> steps,
            String frontendPath
    ) {
    }

    public record ApiEndpoint(
            String method,
            String path,
            String service,
            String description,
            String frontendPath
    ) {
    }

    public record CommandGroup(
            String name,
            List<CommandItem> items
    ) {
    }

    public record CommandItem(
            String label,
            String command
    ) {
    }

    public record ServiceHealth(
            String service,
            String name,
            String kind,
            String url,
            String status,
            String message,
            long checkedAt
    ) {
    }
}
