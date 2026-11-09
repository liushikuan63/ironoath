package com.ironoath.core.stage;

import com.ironoath.core.army.UnitKind;
import java.util.Objects;

/** B09 player entry rule, shared by the real stage service and its offline diagnostic. */
public enum StageUnitRule {
    NONE, NO_SIEGE, CAVALRY_ONLY, RANGED_ONLY;

    /** Applies to the player's deployment only; never filter the defending army with this rule. */
    public boolean allows(UnitKind kind) {
        Objects.requireNonNull(kind,"stage deployment needs a known unit kind");
        return switch(this) {
            case NONE -> true;
            case NO_SIEGE -> kind != UnitKind.SIEGE;
            case CAVALRY_ONLY -> kind == UnitKind.CAVALRY;
            case RANGED_ONLY -> kind == UnitKind.ARCHER || kind == UnitKind.SIEGE;
        };
    }
}
