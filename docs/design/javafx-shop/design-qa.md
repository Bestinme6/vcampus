# JavaFX 商店设计验收记录

## 验收范围

2026-09-05 使用 `ShopVisualTest` 的确定性数据渲染原生 JavaFX 商店。基础页面在 1440×900、1280×800 和 1000×720 三种窗口尺寸检查；边界状态在 1000×720 检查。窗口较短时页面通过单一纵向滚动容器继续访问下方内容，不出现水平滚动或嵌套双纵向滚动。

## 生成截图

- 商品列表：`catalog-1440.png`、`catalog-1280.png`、`catalog-1000.png`；
- 商品详情：`detail-1440.png`、`detail-1280.png`、`detail-1000.png`；
- 购物车：`cart-1440.png`、`cart-1280.png`、`cart-1000.png`；
- 订单确认：`checkout-1440.png`、`checkout-1280.png`、`checkout-1000.png`；
- 商品编辑：`editor-1440.png`、`editor-1280.png`、`editor-1000.png`；
- 边界状态：`state-empty.png`、`state-long-name.png`、`state-no-image.png`、`state-broken-image.png`、`state-disabled.png`、`state-insufficient-stock.png`。

## 人工检查与修正

人工查看了 `catalog-1000.png`、`detail-1000.png`、`cart-1000.png`、`checkout-1000.png`、`editor-1000.png` 和 `state-broken-image.png`；修正后再次查看 `editor-1000.png`。

检查确认：商品卡片在窄窗口保持两列且可滚动；详情页价格、库存、数量和双购买入口清晰；购物车不可结算项有原因提示；订单确认页不会提前扣款；坏图显示占位文案且商品信息与购买按钮仍可用。初次检查发现编辑器分类显示英文枚举且图片槽位只有文字，已改为中文分类名称并加入真实缩略图预览。

自动截图只能验证确定性布局和边界状态；真实 MySQL、服务端图片目录、多客户端并发与网络中断仍按 `docs/shop.md` 的手工验收清单执行。
