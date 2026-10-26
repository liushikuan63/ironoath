package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;

/**
 * A city prerequisite whose public text is constructed only from validated numeric levels.
 * Ordinary BizException detail remains diagnostic and must never be copied into the public message.
 */
public final class CityMainLevelRequirementException extends BizException {
    private static final long serialVersionUID = 1L;
    private final int requiredLevel;
    private final int currentLevel;

    public CityMainLevelRequirementException(int requiredLevel, int currentLevel) {
        super(ErrorCode.CITY_MAIN_LEVEL_LOW, diagnostic(requiredLevel, currentLevel));
        this.requiredLevel = requiredLevel;
        this.currentLevel = currentLevel;
    }

    public String playerMessage() {
        return "需要主城 " + requiredLevel + " 级，当前主城 " + currentLevel + " 级";
    }

    private static String diagnostic(int requiredLevel, int currentLevel) {
        if (requiredLevel < 1 || currentLevel < 0 || currentLevel >= requiredLevel) {
            throw new IllegalArgumentException("Invalid main-city prerequisite levels");
        }
        return "需要 主城 " + requiredLevel + " 级，当前 主城 " + currentLevel + " 级";
    }
}
