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
    parent: Node | null
    children: ReadonlyArray<Node>
    /**
     * 按名字取子节点，取不到返回 null —— 与 Cocos Creator 3.8 的真实签名一致。
     * 桩里漏这个 API 会让 headless 类型检查把「用了个真有的引擎方法」报成属性不存在，
     * 那是桩的缺口，不是调用方写错（本项目的 scene/ 层在 Creator 里能编过）。
     */
    getChildByName(name: string): Node | null
    layer: number
    addChild(child: Node): void
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
  }

  /** 矢量绘图组件。占位美术用它在运行时画纯色块，不需要任何图片资源。 */
  export class Graphics extends Component {
    fillColor: Color
    strokeColor: Color
    lineWidth: number
    clear(): void
    rect(x: number, y: number, w: number, h: number): void
    roundRect(x: number, y: number, w: number, h: number, r: number): void
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
    type: number
    sizeMode: number
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
    static HorizontalAlign: { LEFT: number; CENTER: number; RIGHT: number }
    static VerticalAlign: { TOP: number; CENTER: number; BOTTOM: number }
    static Overflow: { NONE: number; CLAMP: number; SHRINK: number; RESIZE_HEIGHT: number }
  }

  export class Canvas extends Component {
    static readonly designResolution: Size
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
  getStorageSync(key: string): string | undefined
  setStorageSync(key: string, value: string): void
  /** wx.login：取一次性登录凭证 code，服务端用它换 openid（B15 §三）。 */
  login(options: {
    success?(res: { code: string; errMsg: string }): void
    fail?(err: { errMsg: string }): void
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
