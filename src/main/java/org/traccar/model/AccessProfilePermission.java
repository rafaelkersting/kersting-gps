package org.traccar.model;

import org.traccar.storage.StorageName;

@StorageName("tc_access_profile_permissions")
public class AccessProfilePermission {

    private long profileId;
    private String permissionKey;

    public long getProfileId() {
        return profileId;
    }

    public void setProfileId(long profileId) {
        this.profileId = profileId;
    }

    public String getPermissionKey() {
        return permissionKey;
    }

    public void setPermissionKey(String permissionKey) {
        this.permissionKey = permissionKey;
    }
}
