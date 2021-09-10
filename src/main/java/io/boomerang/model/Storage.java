package io.boomerang.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties
public class Storage {

  
  private Boolean enable;

  private String size;
  
  /*
   * Leave blank for clusters default to be used
   */
  private String className;
  
  /*
   * Supported values: 
   * - ReadOnlyMany
   * - ReadWriteMany
   * - ReadWriteOnce
   */
  private String accessMode;

  public Boolean getEnable() {
    return enable;
  }

  public void setEnable(Boolean enable) {
    this.enable = enable;
  }

  public String getSize() {
    return size;
  }

  public void setSize(String size) {
    this.size = size;
  }

  public String getClassName() {
    return className;
  }

  public void setClassName(String className) {
    this.className = className;
  }

  public String getAccessMode() {
    return accessMode;
  }

  public void setAccessMode(String accessMode) {
    this.accessMode = accessMode;
  }

}
