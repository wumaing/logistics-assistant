package com.yymm.monitor

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口。
 *
 * 职责：
 * 1. 初始化 Hilt 依赖注入容器。
 * 2. 后续可在此初始化全局配置、前台服务等。
 */
@HiltAndroidApp
class YymmMonitorApplication : Application()
