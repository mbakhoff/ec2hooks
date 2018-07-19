package com.zeroturnaround.ec2hooks;

import org.kohsuke.stapler.StaplerRequest;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;

@Extension
public class ScriptConfig extends GlobalConfiguration {

  private String script;

  public ScriptConfig() {
    load();
  }

  public String getScript() {
    return script;
  }

  public void setScript(String script) {
    this.script = script;
  }

  @Override
  public boolean configure(StaplerRequest req, JSONObject json) {
    req.bindJSON(this, json);
    save();
    return true;
  }
}
