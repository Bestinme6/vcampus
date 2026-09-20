/**
 * JavaFX 主客户端的应用外壳、会话状态与一级页面路由。
 *
 * <p>各业务子包使用“网关—控制器—视图”分层：网关调用 Socket 客户端，控制器在后台执行请求并管理状态，
 * 视图只在 JavaFX 应用线程更新。</p>
 *
 * @since 1.0
 */
package com.vcampus.client.fx;
