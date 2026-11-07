package com.droiddeck.launcher.chrome;

import java.net.URI;

final class ChromeAddress {
    private ChromeAddress() { }

    static String normalize(String input) {
        if (input == null) throw new IllegalArgumentException("Missing address");
        String address = input.trim();
        if (address.isEmpty() || address.length() > 4096 ||
                address.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid address");
        }
        boolean explicitScheme = address.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*");
        boolean hostWithPort = address.matches("^[^/\\s?#:]+:[0-9]+(?:[/?#].*)?$");
        if (!explicitScheme || hostWithPort) address = "https://" + address;
        try {
            URI uri = new URI(address);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) ||
                    uri.getHost() == null || uri.getHost().isEmpty() || uri.getUserInfo() != null ||
                    uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException("Unsupported address");
            }
            return address;
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("Invalid address", error);
        }
    }
}
