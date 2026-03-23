package io.github.boomerang.tests;

import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.InvalidConfigurationException;
import org.testcontainers.dockerclient.TransportConfig;

import java.io.File;
import java.net.URI;

/**
 * Testcontainers Docker client strategy for macOS + Colima.
 *
 * <p>Colima exposes its Docker socket at {@code ~/.colima/default/docker.sock} rather than
 * {@code /var/run/docker.sock}. The built-in {@code UnixSocketClientProviderStrategy} hard-codes
 * the latter path, so this class bridges the gap.
 *
 * <p>Activated automatically when the Colima socket is present (handled by the Maven Surefire
 * {@code colima} profile in {@code boomerang-tests/pom.xml}).
 */
public class ColimaDockerClientProviderStrategy extends DockerClientProviderStrategy {

    static final String SOCKET_PATH =
            System.getProperty("user.home") + "/.colima/default/docker.sock";

    @Override
    public TransportConfig getTransportConfig() throws InvalidConfigurationException {
        File socket = new File(SOCKET_PATH);
        if (!socket.exists()) {
            throw new InvalidConfigurationException(
                    "Colima socket not found at " + SOCKET_PATH);
        }
        return TransportConfig.builder()
                .dockerHost(URI.create("unix://" + SOCKET_PATH))
                .build();
    }

    @Override
    public String getDescription() {
        return "Colima unix socket (" + SOCKET_PATH + ")";
    }

    @Override
    protected boolean isApplicable() {
        return new File(SOCKET_PATH).exists();
    }

    @Override
    protected int getPriority() {
        // Higher than UnixSocketClientProviderStrategy (80) so we are preferred on Colima systems
        return 85;
    }
}
