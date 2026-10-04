/**
 * 职责：Cocos Creator 3.8 的 headless 类型桩 —— 仅用于在没有 Cocos 编辑器的环境（CI / 本地 tsc）
 *       对场景层做类型检查，不参与真实运行。
 * 依赖：无。
 *
 * ⚠️ 这不是 cc 的完整声明，只覆盖本工程场景层用到的最小 API 面。
 *   真实类型以 Cocos Creator 安装目录下的 `temp/declarations/cc.d.ts` 为准；
 *   在编辑器里打开工程后，应把 tsconfig 的 paths 指向真实声明文件。
 *   桩与真实 API 若有偏差，tsc 会通过但编辑器会报错 —— 所以场景层改动必须在编辑器里过一遍。
 */
declare module 'cc' {
  /** 装饰器入口。@ccclass 是组件能挂到场景节点上的前提。 */
  export const _decorator: {
    ccclass: (name?: string) => ClassDecorator
    property: (options?: unknown) => PropertyDecorator
  }

  export class Vec3 {
    constructor(x?: number, y?: number, z?: number)
    x: number
    y: number
    z: number
    set(x: number, y: number, z: number): this
  }

  /** 二维向量。触摸事件的坐标与位移用它，不用 Vec3（真实 cc 亦然）。 */
  export class Vec2 {
    constructor(x?: number, y?: number)
    x: number
    y: number
  }

  /**
   * 触摸事件的最小面。地图拖动只用 getDelta，点击只用 getUILocation。
   * 双指缩放需要 getAllTouches，桩里刻意不声明 —— 手势识别应在编辑器里接，见 WorldMap 的说明。
   */
  export class EventTouch {
    getUILocation(): Vec2
    getDelta(): Vec2
    /** 当前仍在屏幕上的全部触点；用于区分单指拖动与双指缩放。 */
    getAllTouches(): Touch[]
  }

  export class Touch {
    getID(): number | null
    getUILocation(): Vec2
  }

  /**
   * 鼠标事件的最小面。Web 端内城镜头用滚轮缩放（`mouse-wheel`），只读 getScrollY。
   * 真机没有滚轮，那条路径由双指捏合与两颗缩放键覆盖。
   */
  export class EventMouse {
    getScrollY(): number
    getUILocation(): Vec2
  }

  export class Size {
    constructor(width?: number, height?: number)
    width: number
    height: number
  }

  export class Rect {
    constructor(x?: number, y?: number, width?: number, height?: number)
    x: number
    y: number
    width: number
    height: number
  }

  export class Color {
    constructor(r?: number, g?: number, b?: number, a?: number)
    r: number
    g: number
    b: number
    a: number
    static WHITE: Color
    static BLACK: Color
  }

  export class Component {
    node: Node
    enabled: boolean
    /** Cocos 真实属性：组件所属节点树尚未销毁时为 true。异步回调回来先问它。 */
    isValid: boolean
    onLoad?(): void
    start?(): void
    update?(deltaTime: number): void
    onEnable?(): void
    onDisable?(): void
    onDestroy?(): void
    getComponent<T extends Component>(type: { new(): T }): T | null
    addComponent<T extends Component>(type: { new(): T }): T
    schedule(callback: () => void, interval?: number): void
    unschedule(callback: () => void): void
  }

  export class Node {
    constructor(name?: string)
    name: string
    active: boolean
    /** 2D 节点绕 Z 轴旋转角度；城墙上沿需要轻微斜置。 */
    angle: number
    parent: Node | null
    children: ReadonlyArray<Node>
    /**
     * 按名字取子节点，取不到返回 null —— 与 Cocos Creator 3.8 的真实签名一致。
     * 桩里漏这个 API 会让 headless 类型检查把「用了个真有的引擎方法」报成属性不存在，
     * 那是桩的缺口，不是调用方写错（本项目的 scene/ 层在 Creator 里能编过）。
     */
    getChildByName(name: string): Node | null
    layer: number
    /** 局部坐标。地图命中测试要读它，只给 setPosition 不给读是半个 API。 */
    position: Vec3
    addChild(child: Node): void
    /**
     * 改兄弟序号（越大越靠后、画得越上面）。真实引擎有，桩里此前漏了它 ——
     * 于是弹层抬层只能用 `parent.addChild(自己)`，而那在 3.8.7 里对同一父节点是空操作
     * （实测 children 为 A,B 时再 addChild(A) 仍是 A,B），抬层一直没生效。
     */
    setSiblingIndex(siblingIndex: number): void
    removeFromParent(): void
    destroy(): boolean
    setPosition(position: Vec3): void
    setPosition(x: number, y: number, z?: number): void
    /**
     * 设置缩放。真实 Creator 3.8 有两个重载 —— 桩里漏了它会让 headless 类型检查
     * 把「用了个真有的引擎方法」报成「属性不存在」（与 getChildByName 同一条注释）。
     */
    setScale(scale: Vec3): void
    setScale(x: number, y: number, z?: number): void
    addComponent<T extends Component>(type: { new(): T }): T
    getComponent<T extends Component>(type: { new(): T }): T | null
    /**
     * 注册事件。回调的事件参数用泛型 T 表达 —— 原来写成 `(...args: unknown[]) => void`，
     * 在 strictFunctionTypes 下会拒绝 `(e: EventTouch) => void`（unknown 不可赋给 EventTouch）。
     * 真实 cc 的签名是按事件名查表（`NodeEventMap[T]`），桩里不做那张表，由调用方自己声明。
     */
    on<T>(type: string, callback: (event: T, ...args: unknown[]) => void, target?: unknown): void
    off<T>(type: string, callback?: (event: T, ...args: unknown[]) => void, target?: unknown): void
  }

  export class UITransform extends Component {
    width: number
    height: number
    setContentSize(size: Size): void
    setContentSize(width: number, height: number): void
    setAnchorPoint(x: number, y: number): void
    /** 世界（UI 屏幕）坐标折到本节点局部系：地图点选命中用的就是它。 */
    convertToNodeSpaceAR(worldPoint: Vec3): Vec3
  }

  /** 矢量绘图组件。占位美术用它在运行时画纯色块，不需要任何图片资源。 */
  export class Graphics extends Component {
    fillColor: Color
    strokeColor: Color
    lineWidth: number
    clear(): void
    rect(x: number, y: number, w: number, h: number): void
    /** 实心矩形。与 rect()+fill() 相同，只是少一步（真实 API 两者都有）。 */
    fillRect(x: number, y: number, w: number, h: number): void
    roundRect(x: number, y: number, w: number, h: number, r: number): void
    circle(cx: number, cy: number, r: number): void
    ellipse(cx: number, cy: number, rx: number, ry: number): void
    arc(cx: number, cy: number, r: number, startAngle: number, endAngle: number,
        counterclockwise?: boolean): void
    moveTo(x: number, y: number): void
    lineTo(x: number, y: number): void
    close(): void
    fill(): void
    stroke(): void
  }

  export class SpriteFrame {
    constructor()
    insetLeft: number
    insetTop: number
    insetRight: number
    insetBottom: number
    texture: unknown
    rect: Rect
    originalSize: Size
    offset: Vec2
    packable: boolean
  }

  export class Texture {
    static WrapMode: {
      REPEAT: number
      CLAMP_TO_EDGE: number
      MIRRORED_REPEAT: number
    }
  }

  export class Texture2D extends Texture {
    setWrapMode(wrapS: number, wrapT: number): void
  }

  export class Sprite extends Component {
    spriteFrame: SpriteFrame | null
    /**
     * 真实引擎里 `Sprite extends UIRenderer`，`color` 是 UIRenderer 上的属性（染色用），
     * 桩里漏了它会把"用了个真有的引擎方法"报成属性不存在 —— 与 `setSiblingIndex`、
     * `getChildByName` 同一条：那是桩的缺口，不是调用方写错（本项目的 scene/ 层在 Creator 里能编过）。
     */
    color: Color
    type: number
    sizeMode: number
    /** `Sprite` 继承自 2D 渲染器的着色，例如城景描边垫图需要它。 */
    color: Color
    static Type: {
      SIMPLE: number
      SLICED: number
      TILED: number
      FILLED: number
    }
    static SizeMode: {
      CUSTOM: number
      TRIMMED: number
      RAW: number
    }
  }

  export class Label extends Component {
    string: string
    fontSize: number
    lineHeight: number
    useSystemFont: boolean
    fontFamily: string
    color: Color
    horizontalAlign: number
    verticalAlign: number
    overflow: number
    enableWrapText: boolean
    static HorizontalAlign: { LEFT: number; CENTER: number; RIGHT: number }
    static VerticalAlign: { TOP: number; CENTER: number; BOTTOM: number }
    static Overflow: { NONE: number; CLAMP: number; SHRINK: number; RESIZE_HEIGHT: number }
  }

  /**
   * 文字描边组件。城景是亮暗交错的厚涂，小字压在上面没有描边就读不出来
   * （内城建筑名 1:1 目视实测：字与城墙同亮度时几乎隐形）。真实 cc 有同名组件。
   */
  export class LabelOutline extends Component {
    color: Color
    width: number
  }

  export class Canvas extends Component {
    static readonly designResolution: Size
  }

  /**
   * 文本输入框（B22 聊天用）。桩只声明本工程用到的面：文本、占位符、上限、
   * 标签、单行模式与三个事件名，其余（密码位、Tab 序、回车类型）不声明 ——
   * 需要时应在编辑器里对着真实声明补。
   *
   * <p>**`maxLength` 必须显式设置**：真实实现的默认上限是 20 个字符，
   * 不设就等于把玩家的半句话静默截断（口径见 `game/social/ChatPanel.CHAT_INPUT_MAX_LENGTH`）。
   */
  export class EditBox extends Component {
    string: string
    placeholder: string
    maxLength: number
    textLabel: Label | null
    placeholderLabel: Label | null
    inputMode: number
    static InputMode: { ANY: number; SINGLE_LINE: number }
    static EventType: {
      EDITING_DID_BEGAN: string
      EDITING_DID_ENDED: string
      TEXT_CHANGED: string
      EDITING_RETURN: string
    }
  }

  export class Widget extends Component {
    isAlignTop: boolean
    isAlignLeft: boolean
    isAlignRight: boolean
    isAlignBottom: boolean
    top: number
    left: number
    right: number
    bottom: number
    alignMode: number
    updateAlignment(): void
  }

  export const Layers: { Enum: { UI_2D: number; DEFAULT: number } }

  export const view: {
    getVisibleSize(): Size
    getDesignResolutionSize(): Size
  }

  /**
   * 音频资源与播放组件（A14）。桩里补它们是因为 `scene/AudioService.ts` 用到了，
   * 而 headless 类型检查会把「真有的引擎 API」报成属性不存在（与 getChildByName 同一条注释）。
   */
  export class AudioClip {
  }

  export class AudioSource extends Component {
    clip: AudioClip | null
    /** 挂上就播。音效层要求它必须是 false（播放时机由 `Sfx` 的三个角色决定）。 */
    playOnAwake: boolean
    loop: boolean
    playOneShot(clip: AudioClip, volume?: number): void
  }

  /** 引擎级输入：音效接点挂在它的 TOUCH_START（触摸设备）与 MOUSE_DOWN（桌面 Web）上。 */
  export class Input {
    /**
     * 2026-10-04 补全鼠标那六个：真实引擎的 `Input.EventType` 本来就有它们，
     * 是**本桩只写了 touch 三个**。补之前 `Input.EventType.MOUSE_DOWN` 编译不过。
     *
     * <p>为什么不改用字符串字面量绕过：`bindGlobalTouch` 挂错事件名**不会报错、只会永远不响**
     * （本轮 `verify-audio-runtime` 的 `armed` 恒为 false 就是这类症状）⇒ 值必须来自引擎常量。
     */
    static EventType: {
      TOUCH_START: string
      TOUCH_MOVE: string
      TOUCH_END: string
      TOUCH_CANCEL: string
      MOUSE_DOWN: string
      MOUSE_UP: string
      MOUSE_MOVE: string
      MOUSE_WHEEL: string
      MOUSE_ENTER: string
      MOUSE_LEAVE: string
    }
  }

  export const input: {
    on<T>(type: string, callback: (event: T, ...args: unknown[]) => void, target?: unknown): void
    off<T>(type: string, callback?: (event: T, ...args: unknown[]) => void, target?: unknown): void
  }

  /** 应用级事件（`Game.EVENT_HIDE` 之类）。回前台补播抑制读的就是 EVENT_HIDE。 */
  export class Game {
    static readonly EVENT_HIDE: string
    static readonly EVENT_SHOW: string
  }

  export const director: {
    getScene(): Node | null
    loadScene(sceneName: string, onComplete?: (error: Error | null) => void): void
  }

  export const resources: {
    load<T>(path: string, type: { new(...args: unknown[]): T },
            onComplete: (error: Error | null, asset: T) => void): void
  }

  export class JsonAsset {
    json: unknown
  }

  export const game: {
    frameRate: number
    on(type: string, callback: () => void): void
  }

  export const sys: {
    now(): number
    platform: string
    isBrowser: boolean
    /**
     * 跨平台存储（Web 走 localStorage、微信小游戏走 wx.setStorageSync 那一套）。
     * 桩里补它是因为「记住本机账号」只能用这个入口 —— 直接摸 window.localStorage
     * 在小游戏上根本不存在。
     */
    localStorage: {
      getItem(key: string): string | null
      setItem(key: string, value: string): void
      removeItem(key: string): void
    }
  }
}

/**
 * 微信小游戏全局对象的类型桩。真实声明来自 miniprogram-api-typings；
 * 这里只声明本工程 NetModule 的 wx 适配器用到的部分。
 */
declare const wx: {
  request(options: {
    url: string
    method?: 'GET' | 'POST'
    data?: unknown
    header?: Record<string, string>
    timeout?: number
    success?(res: { statusCode: number; data: unknown }): void
    fail?(err: { errMsg: string }): void
  }): { abort(): void }
  connectSocket(options: {
    url: string
    header?: Record<string, string>
    success?(): void
    fail?(err: { errMsg: string }): void
  }): WxSocketTask
  /** 应用回到前台（小游戏切回前台、浏览器标签页重新可见时会走同一条）。 */
  onShow(callback: () => void): void
  getStorageSync(key: string): string | undefined
  setStorageSync(key: string, value: string): void
  /** wx.login：取一次性登录凭证 code，服务端用它换 openid（B15 §三）。 */
  login(options: {
    success?(res: { code: string; errMsg: string }): void
    fail?(err: { errMsg: string }): void
  }): void
  /**
   * wx.requestMidasPayment：拉起米大师支付（B19 S3-iv）。**只有小游戏运行时才有**，
   * 浏览器/编辑器里不存在 —— 桥用它是否可调用来判"这个环境支持不支持支付"。
   *
   * <p>参数与回调形状**须以米大师正式文档复核**（本地没有凭据，验不了真实扣款）。
   * errCode === -2 是官方"用户取消"的口径。
   */
  requestMidasPayment(options: {
    mode: string
    offerId: string
    buyQuantity: string
    env: string
    currencyType: string
    platform?: 'android' | 'ios'
    success?(): void
    fail?(err: { errMsg: string; errCode?: number }): void
  }): void
}

interface WxSocketTask {
  send(options: { data: string; fail?(err: { errMsg: string }): void }): void
  close(options?: { code?: number; reason?: string }): void
  onOpen(callback: (res: unknown) => void): void
  onMessage(callback: (res: { data: string }) => void): void
  onClose(callback: (res: unknown) => void): void
  onError(callback: (res: { errMsg: string }) => void): void
}
