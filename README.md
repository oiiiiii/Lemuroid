## 源仓库
地址：https://github.com/Swordfish90/Lemuroid

**有什么问题去原项目反馈，我只针对自己使用过程做了如下修改。**

## 优化
- 优化了搜索功能，尤其是对中文的支持。
- 新增外置运行动态库文件，方便连不上外网，无法使用程序内置方式加载核心的问题。
- 下载核心在：https://buildbot.libretro.com/nightly/android/latest/
- 设置>>游戏核心>>所需游戏核心可以查看当前选择的ROM库缺少哪些动态库文件，在上面网址下载zip压缩包后解压出.so文件放在核心目录下就可以了。
- 把游戏加载过程放在子线程进行，避免主线程堵塞。

## 下载

安装包：https://diaoyu.lanzouu.com/i5l5S3jvhopa

v8a的全部动态库（20个）：https://diaoyu.lanzouu.com/iel8X3jvhplc