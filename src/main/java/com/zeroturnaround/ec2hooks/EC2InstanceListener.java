package com.zeroturnaround.ec2hooks;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Computer;
import hudson.remoting.Channel;
import hudson.slaves.ComputerListener;
import hudson.slaves.OfflineCause;
import hudson.util.StreamCopyThread;
import jenkins.model.GlobalConfiguration;

@Extension
public class EC2InstanceListener extends ComputerListener {

  private final Map<Computer, ComputerState> states = new HashMap<>();

  @Override
  public void preOnline(Computer c, Channel channel, FilePath root, TaskListener listener) throws IOException, InterruptedException {
    if (c instanceof EC2Computer) {
      ComputerState state = states.computeIfAbsent(c, comp -> new ComputerState());
      state.preOnline((EC2Computer) c, listener);
    }
  }

  @Override
  public void onOffline(Computer c, OfflineCause cause) {
    ComputerState state = states.remove(c);
    if (state != null) {
      state.close();
    }
  }

  static class ComputerState implements AutoCloseable {

    final List<Path> tempFiles = new ArrayList<>();
    final List<Process> processes = new ArrayList<>();

    void preOnline(EC2Computer c, TaskListener listener) throws IOException, InterruptedException {
      EC2AbstractSlave node = c.getNode();
      if (node == null)
        return;

      ScriptConfig config = GlobalConfiguration.all().get(ScriptConfig.class);
      String script = config != null ? config.getScript() : null;
      if (script == null || script.trim().isEmpty())
        return;

      script = script.replace("\r\n", "\n");

      try {
        Path identityKeyFile = createTempFile(c.getCloud().getPrivateKey().getPrivateKey(), ".pem");
        tempFiles.add(identityKeyFile);

        Path scriptFile = createTempFile(script, ".sh");
        tempFiles.add(scriptFile);

        EnvVars vars = new EnvVars();
        vars.put("ssh_identity", identityKeyFile.toString());
        vars.put("ssh_port", String.valueOf(node.getSshPort()));
        vars.put("ssh_user", node.remoteAdmin);
        vars.put("ssh_ip", getRemoteAddress(c));
        vars.put("node_name", node.getNodeName());
        vars.put("node_labels", node.getLabelString());
        vars.put("ec2_image", c.describeInstance().getImageId());

        ProcessBuilder pb = new ProcessBuilder("bash", scriptFile.toString());
        pb.redirectErrorStream(true);
        pb.environment().putAll(c.getEnvironment());
        pb.environment().putAll(vars);

        Process p = pb.start();
        processes.add(p);
        new StreamCopyThread("ec2hooks", p.getInputStream(), listener.getLogger()).start();
      }
      catch (Exception e) {
        close();
        throw e;
      }
    }

    private String getRemoteAddress(EC2Computer c) throws InterruptedException {
      String address = c.describeInstance().getPublicDnsName();
      if (address == null || address.trim().isEmpty())
        address = c.describeInstance().getPublicIpAddress();
      return address;
    }

    private Path createTempFile(String content, String suffix) throws IOException {
      Path tempFile = File.createTempFile("ec2hooks_", suffix).toPath().toAbsolutePath();
      try {
        Files.write(tempFile, content.getBytes(UTF_8));
        Files.setPosixFilePermissions(tempFile, EnumSet.of(PosixFilePermission.OWNER_READ));
        return tempFile;
      } catch (Exception e) {
        Files.delete(tempFile);
        throw e;
      }
    }

    public void close() {
      List<Exception> exceptions = new ArrayList<>();
      for (Process process : processes) {
        try {
          process.destroy();
        } catch (Exception e) {
          exceptions.add(e);
        }
      }
      for (Process process : processes) {
        try {
          process.waitFor(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      for (Path file : tempFiles) {
        try {
          Files.delete(file);
        } catch (Exception e) {
          exceptions.add(e);
        }
      }
      if (!exceptions.isEmpty()) {
        RuntimeException failure = new RuntimeException("ec2hooks cleanup failed");
        for (Exception exception : exceptions) {
          failure.addSuppressed(exception);
        }
        throw failure;
      }
    }
  }
}
