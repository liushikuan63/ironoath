# Cocos 3.8 UI 输入命中：引擎实测备忘

> 这份备忘里的每一条都从**本仓产物** `client/build/web-mobile/cocos-js/cc.js`（压缩源码，1.97 MB）里抄出来，
> 不是从文档或记忆里推的。复核命令见文末「怎么复核」——**条款与产物对不上时，以产物为准，改这份文件**。
>
> 起因：收口清单 #624~#632 一串「体力弹层点不中」的排查里，坐标换算反复出错，而每一次错法都被
> 当成了产品缺陷的证据。下面第 5、6 两条就是那串返工的直接原因。

## 一、坑位清单

### 1. `screenToWorld` 的入参必须是 `Vec3`，不能是散参或 `Vec2`

传 `(x, y, z, out)` 四个散参会被当成「拿 out 当 screenPoint」，报
`Cannot create property 'x' on number`；传 `Vec2` **静默给 NaN**（不报错，最难查）。
（#624 实测两个都踩过。）

### 2. `UITransform` 没有 `containsPoint`

实测报 `bx.containsPoint is not a function`。
**但它有官方的 `hitTest`——见第 6 条。自己复刻盒判定是错的做法。**

### 3. 遍历场景树要自己加深度上限

递归 `node.children` 不设上限，遇到自引用/长链会栈溢出。探针里统一 `if (depth > 40) return`。

### 4. 跨行 `console.log` 的括号与 `\n`

`page.evaluate` 里的 `console.log` 进的是**页面**上下文，不会出现在 node 的 stdout。
必须先收集到局部变量、再随返回值带出去；拼多行用 `String.fromCharCode(10)`，
不要在压缩/转译环境里赌 `\n` 能过括号匹配。（#610 栽在这里：什么都没打出来。）

### 5. ★ `Camera.screenToWorld(OUT, IN)` —— **输出向量是第一个参数**

引擎自己的包装函数写死了这个顺序：

```js
// convertUtils 里的包装
i.screenToWorld = function (t, e) {
  return e || (e = this.node.getWorldPosition()),      // e = 输入
         this._camera && this._camera.screenToWorld(e, t), // camera.screenToWorld(OUT=t, IN=e)
         e                                             // 返回被就地改写的「输入」那个
}
```

`Camera` 上两个方向的签名一致：**`out` 在前、点在后**。
`worldToScreen` 同样是 `worldToScreen(OUT, IN)`（内部 `transformMat4(t, e, matViewProj)`，返回 `t`）。

**踩过的那次**：`cam.screenToWorld(sp, world)` —— 传反了。于是 `world` 始终停在 `(0, 0, 0)`，
后面整段「把点击点反投影回世界坐标再算局部坐标」的诊断量的其实是**世界原点**，与点击点无关。
#624 据此得出的「往返不一致 (0, -364)」是这么来的；#626 把它归因为「漏了 y 轴翻转」——
**归因同样可疑**，因为那条诊断的输入一开始就是错的。

### 6. ★ 官方命中 API 是 `UITransform.hitTest(screenPoint, windowId)`

引擎处理输入时就是这么调的（`UIInputManager` 的鼠标分支）：

```js
e._handleMouseDown = function (t) {
  var e = this._node, i = e._getUITransformComp();
  return !(!e || !i || (t.getLocation(ly), !i.hitTest(ly, t.windowId) || ...))
}
```

要点，逐条对着实现核过：

- **入参是「屏幕点」，不是世界点**。实现里是 `h.screenToWorld(r, r)` —— 世界坐标换算是它自己做的，
  调用方**不要**先换算再传。
- `windowId` 省略时**默认 0**；实现里逐个相机比对 `h.systemWindowId === windowId`。
- 它比手写盒判定**多做三件事**，缺任何一条都会得到假绿：
  ① `camera.visibility & this.node.layer`（相机可见性位 ∧ 节点层位）；
  ② `camera.systemWindowId === windowId`；
  ③ `this._maskTest(point)`（遮罩）。
- 还会按 `this._anchorPoint` 做偏移补偿后再判 `0 <= x <= contentSize.width`。
- 遍历 `this._getRenderScene().cameras`，**任一相机**命中即返回 true。

⇒ 结论：**判「这个点命不命中某个节点」不要自己算，直接 `nodeHit.getComponent('cc.UITransform').hitTest(点, windowId)`。**

### 7. 命中顺序：`_sortByPriority`（附）
决定「谁先被测」的不是渲染直觉，而是这个排序函数：

1. 先比 `cachedCameraPriority`（降序）；
2. 再沿父链上溯到**共同祖先**，比那一层的 `siblingIndex`（**同父时升序 ⇒ 先添加的先被命中**）。

⇒ **UI 渲染顺序与输入命中顺序都建立在 `siblingIndex` 上，方向相反**。
「底板画在格子下面、同时不吃输入」这个组合，调 `siblingIndex` / `addChild` 顺序达不到。

⚠️ `cachedCameraPriority` 来自 `UITransform.cameraPriority`，而它是个**只读 getter**
（取自渲染相机的 priority），**不是**能写上去的开关。

### 8. ★★「屏幕点」到底是哪个空间 —— CSS 像素**不是**它（#633 踩的就是这条）

第 6 条说 `hitTest` 吃「屏幕点」，但**没说是哪个屏幕点**。产物里浏览器事件进引擎的那一步写死了：

```js
// pal/input/web/mouse-input.ts 压缩后（cc.js 内）
e._getLocation = function (t, e) {
  var i = t.clientX - e.x,                 // e = canvas.getBoundingClientRect()
      n = e.y + e.height - t.clientY;      // y 从下往上翻
  if (Ja.isFrameRotated) { var r = i; i = e.height - n; n = r }
  var s = Ja.devicePixelRatio;
  return new os(i *= s, n *= s)            // 只乘 dpr
}
```

⇒ 喂给 `hitTest` 的那个点 = **`(clientX - rect.left) * dpr`，`(rect.top + rect.height - clientY) * dpr`**，
单位是**帧缓冲像素**、原点在**左下角**。于是三条必须记住的：

1. **只乘 dpr**。不减 viewport 原点、不除 `getScaleX()`、不按 `getVisibleSizeInPixel()` 归一化。
   拿 `getVisibleSizeInPixel()` 当分母是最常见的错法 —— 那是**设计空间**尺寸，不是帧缓冲尺寸。
2. **y 与 CSS 相反**。CSS 原点在左上，引擎原点在左下。`cssY + engineY = rect.top + rect.height`。
   把 CSS 点直接塞进 `hitTest`，等于在**上下镜像**的那片区域里找命中。
3. **`getUILocation` 才是 UI 空间**（`(p.x - viewport.x) / getScaleX()`，不翻 y），
   而 `hitTest` 吃的**不是**它 —— `EventMouse.getLocation()` 与 `getUILocation` 是两个方法，别混。

> ⚠️ 只有 `dpr === 1` 且 canvas 贴在 `(0,0)`、`canvas.width === getVisibleSizeInPixel().width` 时，
> 上面这个式子才与「CSS 除以 visibleSizeInPixel」那个式子**碰巧相等**（本仓默认 1440×900 视口就是这样）。
> 换个视口 / 开了缩放，两者立刻分道扬镳 —— 而量具往往只在**默认值**下跑过一次，于是"点得中"，
> 换个窗口就"点不中"，偏移量怎么调都对不上。**别拿"我这跑通了"当口径成立的证据**（#610/#616/#624 三次返工都是这么来的）。

正确姿势（`tools/lib/cocos-click.mjs` 就是这么写的）：

```js
const world = box.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0))
const sp = cam.worldToScreen(new cc.Vec3(), new cc.Vec3(world.x, world.y, world.z)) // out 在前
if (!box.hitTest(new cc.Vec2(sp.x, sp.y), cam.systemWindowId)) return /* 不可信就别点 */
// 引擎点 → CSS 点（上面那个式子的逆）
const cssX = rect.left + sp.x / dpr
const cssY = rect.top + rect.height - sp.y / dpr
```

自检里那条 `cam.screenToWorld(out2, sp)` 往返**不能省**：它是唯一一条与 CSS 换算、与参数顺序都无关的
独立判据。相机没摆好（`priority` 没给、还没进渲染场景）时它会立刻把这条点击判成不可信，
而不是让你拿着一个退化相机的读数去调偏移。

## 二、怎么复核（条款与产物对不上时以产物为准）

```bash
# 5：输出参数在前 —— 看引擎自己的包装怎么调
grep -o 'i.screenToWorld=function(t,e){[^}]*}' client/build/web-mobile/cocos-js/cc.js

# 6：hitTest 的完整实现（入参是屏幕点、内部自己 screenToWorld）
grep -o 'i.hitTest=function(t,e){.\{0,600\}' client/build/web-mobile/cocos-js/cc.js

# 6：引擎处理输入时怎么调它
grep -o '_handleMouseDown=function(t){.\{0,200\}' client/build/web-mobile/cocos-js/cc.js

# 坐标原点的两个换算（别再自己推）
grep -o 'getUILocationX=function(){.\{0,120\}' client/build/web-mobile/cocos-js/cc.js
grep -o '_convertToUISpace=function(t){.\{0,120\}' client/build/web-mobile/cocos-js/cc.js

# 8：浏览器事件进引擎的那一步（只乘 dpr、y 从下往上）—— 第 6 条的「屏幕点」就指它
grep -o '_getLocation=function(t,e){.\{0,260\}' client/build/web-mobile/cocos-js/cc.js
```

## 三、诊断坐标换算时的正确姿势

**先从 `cc.js` 里把 Cocos 自己那一步抄出来（连 y 翻转一起），再和自己的公式对**，
而不是凭 `worldToScreen` 这个名字猜。想看引擎内部某个函数时，
`node` 侧 `console.log` 不会进 stdout —— 用 `grep` 直接读产物源码更快也更准。
