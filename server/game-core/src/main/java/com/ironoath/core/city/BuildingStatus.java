package com.ironoath.core.city;

/**
 * 职责：建筑实例的建造状态。
 * 依赖：无（纯 Java，game-core 只依赖 game-common）。
 */
public enum BuildingStatus {

    /** 空闲，可开始升级、正常产出资源。 */
    IDLE,

    /** 升级中。<b>不产资源</b>（B03 §2），完成后一次性结算离线产出。 */
    UPGRADING,

    /** 已暂停（队列中可暂停/恢复），同样不产资源。 */
    PAUSED
}
