package org.traccar.model;

import org.traccar.storage.StorageName;

@StorageName("tc_user_access_profiles")
public class UserAccessProfile {

    private long userId;
    private long profileId;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public long getProfileId() {
        return profileId;
    }

    public void setProfileId(long profileId) {
        this.profileId = profileId;
    }
}
