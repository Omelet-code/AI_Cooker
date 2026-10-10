package com.cooker.identity.service;

import com.cooker.identity.dto.SaveDiningProfileRequest;
import com.cooker.identity.dto.UserDiningProfile;
import com.cooker.identity.vo.DiningProfileView;

public interface UserDiningProfileService {
    DiningProfileView currentUserProfile();
    DiningProfileView saveCurrentUser(SaveDiningProfileRequest request);

    /** 仅供可信后端调用：userId须由调用方完成身份和资源归属核验，不能来自未验证的客户端参数。 */
    UserDiningProfile readForUser(long userId);
}
