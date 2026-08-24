package org.traccar.model;

import org.traccar.storage.StorageName;

@StorageName("tc_access_profiles")
public class AccessProfile extends BaseModel {

    private String name;
    private String description;
    private boolean disabled;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean getDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }
}
