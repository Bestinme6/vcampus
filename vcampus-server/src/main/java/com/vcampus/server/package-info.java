/**
 * VCampus 应用服务器的启动入口。
 *
 * <p>服务器在监听 Socket 前完成配置读取和数据库结构准备，随后由网络层接收请求、业务层校验权限，
 * 最终通过数据访问层读写 MySQL。</p>
 *
 * @since 1.0
 */
package com.vcampus.server;
