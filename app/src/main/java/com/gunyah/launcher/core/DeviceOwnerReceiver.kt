package com.gunyah.launcher.core

import android.app.admin.DeviceAdminReceiver

/**
 * device owner 接收器。设为 device owner 后可用 DevicePolicyManager：
 *  - setLockTaskPackages 免确认框进入屏幕固定
 *  - setLockTaskFeatures(NONE) 隐藏状态栏/通知/最近任务/底部导航 pill
 * 从而使 startLockTask 期间整机屏幕完全归 guest，底部任务栏可点、上下手势又被锁。
 */
class DeviceOwnerReceiver : DeviceAdminReceiver()
