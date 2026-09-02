#!/usr/bin/env python3
"""
Generate PDF: 视频识别及物体/人脸识别 代码实现需求文档
Based on 《数字图像与视频处理》教材 and existing project codebase
"""

import os
import sys
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import inch
from reportlab.lib import colors
from reportlab.lib.colors import HexColor
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Image, PageBreak,
    KeepTogether, Table, Flowable
)
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from PIL import Image as PILImage

OUTPUT_PDF = "/Users/wangyao/androidproject/OpencvProcessing/视频识别及物体人脸识别需求文档.pdf"
PDF_SKILL_SCRIPTS = "/Users/wangyao/.trae-cn/builtin/work/default/skills/pdf/scripts"

def register_cjk_font():
    font_paths = [
        "/System/Library/Fonts/STHeiti Medium.ttc",
        "/Library/Fonts/Arial Unicode.ttf",
        "/System/Library/Fonts/Hiragino Sans GB.ttc",
    ]
    for fp in font_paths:
        if os.path.exists(fp):
            pdfmetrics.registerFont(TTFont("CJKFont", fp, subfontIndex=0))
            return "CJKFont"
    raise RuntimeError("No CJK font found")

CJK_FONT = register_cjk_font()

PRIMARY_COLOR = HexColor('#1a365d')
ACCENT_COLOR = HexColor('#2b6cb0')
LIGHT_BG = HexColor('#f7fafc')
BORDER_COLOR = HexColor('#e2e8f0')
TEXT_COLOR = HexColor('#2d3748')
MUTED_COLOR = HexColor('#718096')
CODE_BG = HexColor('#f8f9fa')
TABLE_HEADER_BG = HexColor('#2b6cb0')

PAGE_SIZE = A4
PAGE_WIDTH, PAGE_HEIGHT = PAGE_SIZE
LEFT_MARGIN = 0.75 * inch
RIGHT_MARGIN = 0.75 * inch
TOP_MARGIN = 0.75 * inch
BOTTOM_MARGIN = 0.75 * inch
CONTENT_WIDTH = PAGE_WIDTH - LEFT_MARGIN - RIGHT_MARGIN
CONTENT_HEIGHT = PAGE_HEIGHT - TOP_MARGIN - BOTTOM_MARGIN
IMAGE_MAX_WIDTH = CONTENT_WIDTH * 0.95
IMAGE_MIN_WIDTH = CONTENT_WIDTH * 0.3
IMAGE_MAX_HEIGHT = CONTENT_HEIGHT * 0.6

DASH_REPLACEMENTS = {
    '\u2010': '-', '\u2011': '-', '\u2012': '-', '\u2013': '-',
    '\u2014': '-', '\u2015': '-', '\u2212': '-', '\u00ad': '-',
}

def normalize_text(text):
    for old, new in DASH_REPLACEMENTS.items():
        text = text.replace(old, new)
    return text

def get_styles():
    return {
        'cover_title': ParagraphStyle('CoverTitle', fontName=CJK_FONT, fontSize=26, leading=34,
            textColor=PRIMARY_COLOR, alignment=TA_CENTER, wordWrap='CJK', spaceAfter=10),
        'cover_subtitle': ParagraphStyle('CoverSubtitle', fontName=CJK_FONT, fontSize=15, leading=20,
            textColor=ACCENT_COLOR, alignment=TA_CENTER, wordWrap='CJK', spaceAfter=6),
        'cover_info': ParagraphStyle('CoverInfo', fontName=CJK_FONT, fontSize=11, leading=16,
            textColor=MUTED_COLOR, alignment=TA_CENTER, wordWrap='CJK', spaceAfter=5),
        'h1': ParagraphStyle('H1', fontName=CJK_FONT, fontSize=19, leading=25,
            textColor=PRIMARY_COLOR, spaceBefore=24, spaceAfter=10, wordWrap='CJK'),
        'h2': ParagraphStyle('H2', fontName=CJK_FONT, fontSize=15, leading=21,
            textColor=ACCENT_COLOR, spaceBefore=18, spaceAfter=6, wordWrap='CJK'),
        'h3': ParagraphStyle('H3', fontName=CJK_FONT, fontSize=12.5, leading=17,
            textColor=HexColor('#2c5282'), spaceBefore=12, spaceAfter=4, wordWrap='CJK'),
        'body': ParagraphStyle('Body', fontName=CJK_FONT, fontSize=10, leading=16,
            textColor=TEXT_COLOR, spaceBefore=0, spaceAfter=6, wordWrap='CJK',
            firstLineIndent=0, alignment=TA_JUSTIFY),
        'code': ParagraphStyle('Code', fontName='Courier', fontSize=7.5, leading=11,
            textColor=HexColor('#333333'), spaceBefore=2, spaceAfter=2,
            backColor=CODE_BG, leftIndent=10, rightIndent=10,
            borderColor=BORDER_COLOR, borderWidth=0.5, borderPadding=5),
        'caption': ParagraphStyle('Caption', fontName=CJK_FONT, fontSize=8.5, leading=11,
            textColor=MUTED_COLOR, alignment=TA_CENTER, spaceBefore=3, spaceAfter=10, wordWrap='CJK'),
        'table_cell': ParagraphStyle('TableCell', fontName=CJK_FONT, fontSize=8.5, leading=11,
            wordWrap='CJK', splitLongWords=1),
        'table_header': ParagraphStyle('TableHeader', fontName=CJK_FONT, fontSize=9, leading=11,
            textColor=colors.white, wordWrap='CJK', splitLongWords=1, alignment=TA_CENTER),
    }

class ColoredDivider(Flowable):
    def __init__(self, width, height=2, color=ACCENT_COLOR, space_before=4, space_after=8):
        Flowable.__init__(self)
        self.width = width
        self.height = height
        self.color = color
        self.spaceAfter = space_after
        self.spaceBefore = space_before
    def draw(self):
        self.canv.setFillColor(self.color)
        self.canv.rect(0, 0, self.width, self.height, fill=1, stroke=0)

def title_divider():
    return ColoredDivider(CONTENT_WIDTH, height=3, color=ACCENT_COLOR, space_after=16)
def h1_divider():
    return ColoredDivider(CONTENT_WIDTH * 0.3, height=2, color=ACCENT_COLOR, space_after=8)
def subtle_divider():
    return ColoredDivider(CONTENT_WIDTH, height=1, color=BORDER_COLOR, space_after=8)

def make_table(data, col_widths=None):
    styles = get_styles()
    wrapped = []
    for i, row in enumerate(data):
        style = styles['table_header'] if i == 0 else styles['table_cell']
        wrapped.append([Paragraph(str(cell), style) for cell in row])
    t = Table(wrapped, colWidths=col_widths, repeatRows=1)
    t.setStyle([
        ('BACKGROUND', (0, 0), (-1, 0), TABLE_HEADER_BG),
        ('FONTNAME', (0, 0), (-1, -1), CJK_FONT),
        ('FONTSIZE', (0, 0), (-1, 0), 9),
        ('FONTSIZE', (0, 1), (-1, -1), 8.5),
        ('ALIGN', (0, 0), (-1, -1), 'CENTER'),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('BOTTOMPADDING', (0, 0), (-1, 0), 6),
        ('TOPPADDING', (0, 0), (-1, 0), 6),
        ('BOTTOMPADDING', (0, 1), (-1, -1), 5),
        ('TOPPADDING', (0, 1), (-1, -1), 5),
        ('ROWBACKGROUNDS', (0, 1), (-1, -1), [LIGHT_BG, colors.white]),
        ('GRID', (0, 0), (-1, -1), 0.5, BORDER_COLOR),
    ])
    return t

def safe_image(path):
    pil_img = PILImage.open(path)
    orig_w_px, orig_h_px = pil_img.size
    dpi_x, dpi_y = pil_img.info.get('dpi', (72, 72))
    orig_w_pt = orig_w_px / dpi_x * 72
    orig_h_pt = orig_h_px / dpi_y * 72
    display_w = max(min(orig_w_pt, IMAGE_MAX_WIDTH), IMAGE_MIN_WIDTH)
    scale = display_w / orig_w_pt
    display_h = orig_h_pt * scale
    if display_h > IMAGE_MAX_HEIGHT:
        display_h = IMAGE_MAX_HEIGHT
        display_w = display_w * (IMAGE_MAX_HEIGHT / (orig_h_pt * scale))
    img = Image(path, width=display_w, height=display_h)
    img.hAlign = 'CENTER'
    return img

def add_figure(story, image_path, caption_text, styles):
    if not os.path.exists(image_path):
        return
    img = safe_image(image_path)
    caption = Paragraph(normalize_text(caption_text), styles['caption'])
    block_h = img.drawHeight + 20
    if block_h <= CONTENT_HEIGHT * 0.6:
        story.append(KeepTogether([img, caption]))
    else:
        img.keepWithNext = True
        story.append(img)
        story.append(caption)

def render_graphviz_to_png(dot_code, output_path):
    sys.path.insert(0, PDF_SKILL_SCRIPTS)
    try:
        from render_graphviz import render_graphviz, GraphvizSyntaxError, GraphvizError
        render_graphviz(dot_code, output_path,
                        max_width_in=IMAGE_MAX_WIDTH/72, max_height_in=IMAGE_MAX_HEIGHT/72)
        return True
    except Exception as e:
        print(f"Graphviz render error: {e}")
        return False

def build_document():
    styles = get_styles()
    story = []

    # COVER
    story.append(Spacer(1, 1.5*inch))
    story.append(Paragraph(normalize_text("视频识别及物体/人脸识别"), styles['cover_title']))
    story.append(Paragraph(normalize_text("代码实现需求文档"), styles['cover_title']))
    story.append(title_divider())
    story.append(Spacer(1, 0.3*inch))
    story.append(Paragraph(normalize_text("基于数字图像与视频处理原理的工程实现"), styles['cover_subtitle']))
    story.append(Spacer(1, 0.8*inch))
    story.append(Paragraph(normalize_text("参考教材：《数字图像与视频处理》 第4-6、9-11章"), styles['cover_info']))
    story.append(Paragraph(normalize_text("技术栈：Kotlin + OpenCV 4.x + JNI + Android MediaCodec"), styles['cover_info']))
    story.append(Paragraph(normalize_text("项目：OpencvProcessing (Android 图像处理实验平台)"), styles['cover_info']))
    story.append(Paragraph(normalize_text("日期：2026年9月"), styles['cover_info']))
    story.append(PageBreak())

    # CH1
    story.append(Paragraph(normalize_text("第一章 文档概述"), styles['h1']))
    story.append(h1_divider())
    story.append(Paragraph(normalize_text("1.1 编写目的"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "本文档旨在为\"视频识别及物体/人脸识别\"功能模块的代码实现提供详尽的需求规范与架构设计指引。"
        "文档基于《数字图像与视频处理》教材第4章（图像分割）、第5章（压缩编码原理-运动估计）、"
        "第6章（压缩编码标准-H.264/HEVC运动补偿）、第9章（质量评价-运动信息提取）、"
        "第10章（基于内容的图像和视频检索）和第11章（图像识别-SVM/CNN）的理论知识，"
        "结合项目中已有的imagerecognition、imageCVdeal等模块代码基础，"
        "定义新模块的实现原理、代码架构、接口规范和测试方案。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("1.2 项目背景"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "OpencvProcessing项目是一个围绕《数字图像与视频处理》教材实现的Android图像处理实验平台，"
        "由6个Gradle模块组成：app（UI路由层）、imageCVdeal（OpenCV JNI图像处理）、"
        "imagerecognition（形状识别+模板匹配+视频跟踪）、contentsearch（基于内容的图像检索CBIR）、"
        "qualityevaluation（图像质量评估PSNR/SSIM）、digitalwatermark（数字水印DCT/LSB）。"
        "项目技术栈为Kotlin + Java 11 + C++11 + OpenCV 4.x + AGP 9.2.1，"
        "compileSdk=37，minSdk=24，支持arm64-v8a和armeabi-v7a双架构。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "项目已有的识别相关基础能力包括：Hu不变矩形状识别（5类形状分类）、"
        "NCC归一化互相关模板匹配（金字塔粗到精搜索）、视频模板跟踪（首帧取模板+邻域NCC搜索）、"
        "帧差运动检测（相邻帧差分二值化）。但尚未实现人脸检测、通用目标检测、"
        "Haar级联分类器、HOG行人检测、SVM分类器等高级识别算法。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("1.3 术语与缩略语"), styles['h2']))
    term_data = [
        ["术语", "全称", "说明"],
        ["Haar", "Haar-like Features", "哈尔特征，基于矩形黑白区域亮度差的特征描述子"],
        ["HOG", "Histogram of Oriented Gradients", "梯度方向直方图，用于物体检测的特征描述子"],
        ["SVM", "Support Vector Machine", "支持向量机，二分类监督学习模型"],
        ["CNN", "Convolutional Neural Network", "卷积神经网络，深度学习模型"],
        ["LBP", "Local Binary Pattern", "局部二值模式，纹理特征描述子"],
        ["NCC", "Normalized Cross Correlation", "归一化互相关，模板匹配相似度度量"],
        ["MOG2", "Mixture of Gaussians v2", "混合高斯背景建模算法"],
        ["KNN", "K-Nearest Neighbors", "K近邻背景减除算法"],
        ["ROI", "Region of Interest", "感兴趣区域"],
        ["BMA", "Block Matching Algorithm", "块匹配运动估计算法"],
        ["MV", "Motion Vector", "运动矢量"],
        ["NMS", "Non-Maximum Suppression", "非极大值抑制"],
        ["PCA", "Principal Component Analysis", "主成分分析，用于特征脸方法"],
        ["JNI", "Java Native Interface", "Java原生接口，用于调用C/C++代码"],
    ]
    story.append(make_table(term_data, col_widths=[1.0*inch, 2.3*inch, 3.2*inch]))

    # CH2
    story.append(Paragraph(normalize_text("第二章 需求分析"), styles['h1']))
    story.append(h1_divider())
    story.append(Paragraph(normalize_text("2.1 功能需求"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "本模块需在现有imagerecognition模块基础上扩展，实现三大功能子系统："
        "视频识别子系统、物体识别子系统、人脸识别子系统。各子系统功能需求如下："
    ), styles['body']))

    story.append(Paragraph(normalize_text("2.1.1 视频识别子系统"), styles['h3']))
    func_data1 = [
        ["功能ID", "功能名称", "功能描述", "优先级"],
        ["V-01", "运动目标检测", "基于背景建模（MOG2/KNN）和帧差法检测视频中的运动目标，输出前景掩码和外接矩形框", "高"],
        ["V-02", "目标跟踪", "基于NCC模板匹配和光流法实现对运动目标的连续跟踪，输出跟踪轨迹", "高"],
        ["V-03", "运动估计", "基于块匹配算法（BMA）计算相邻帧间的运动矢量场，可视化运动方向和大小", "中"],
        ["V-04", "视频结构化分析", "对视频进行镜头边界检测、关键帧提取、镜头聚类，输出视频结构化摘要", "中"],
        ["V-05", "多目标检测与跟踪", "在视频中同时检测和跟踪多个运动目标，分配唯一ID并绘制轨迹", "中"],
    ]
    story.append(make_table(func_data1, col_widths=[0.6*inch, 1.2*inch, 3.7*inch, 0.7*inch]))

    story.append(Paragraph(normalize_text("2.1.2 物体识别子系统"), styles['h3']))
    func_data2 = [
        ["功能ID", "功能名称", "功能描述", "优先级"],
        ["O-01", "Haar特征目标检测", "基于Haar级联分类器实现通用目标检测（人脸/眼睛/车身等），支持加载自定义级联模型", "高"],
        ["O-02", "HOG行人检测", "基于HOG特征描述子+SVM分类器实现行人检测，输出检测框和置信度", "高"],
        ["O-03", "形状识别增强", "在现有Hu不变矩形状识别基础上增加更多形状类别和分类精度", "中"],
        ["O-04", "模板匹配增强", "在现有NCC匹配基础上增加多尺度、多角度旋转匹配", "中"],
        ["O-05", "特征点匹配", "基于SIFT/ORB特征点提取与匹配实现物体识别", "中"],
        ["O-06", "视频物体检测", "对视频帧逐帧执行物体检测，输出每帧检测结果和统计摘要", "高"],
    ]
    story.append(make_table(func_data2, col_widths=[0.6*inch, 1.2*inch, 3.7*inch, 0.7*inch]))

    story.append(Paragraph(normalize_text("2.1.3 人脸识别子系统"), styles['h3']))
    func_data3 = [
        ["功能ID", "功能名称", "功能描述", "优先级"],
        ["F-01", "人脸检测", "基于Haar级联分类器实现静态图像人脸检测，支持多尺度检测和NMS去重", "高"],
        ["F-02", "人脸关键点定位", "检测眼睛、鼻子、嘴巴等面部关键点位置", "中"],
        ["F-03", "人脸特征提取", "基于LBP/PCA特征脸方法提取人脸特征向量", "高"],
        ["F-04", "人脸识别", "将检测到的人脸与数据库中已知人脸进行匹配，输出身份识别结果", "高"],
        ["F-05", "视频人脸检测与跟踪", "在视频流中实时检测人脸并跟踪，支持多脸跟踪", "高"],
        ["F-06", "人脸质量评估", "评估检测到的人脸图像质量（清晰度、姿态、遮挡）", "低"],
    ]
    story.append(make_table(func_data3, col_widths=[0.6*inch, 1.2*inch, 3.7*inch, 0.7*inch]))

    story.append(Paragraph(normalize_text("2.2 非功能需求"), styles['h2']))
    nonfunc_data = [
        ["类别", "需求描述", "指标"],
        ["性能-图像", "单张图像人脸/物体检测延迟", "< 500ms (480x480灰度图)"],
        ["性能-视频", "视频逐帧检测处理帧率", ">= 10 fps (480p分辨率)"],
        ["性能-跟踪", "目标跟踪单帧处理延迟", "< 50ms"],
        ["内存", "峰值内存占用", "< 256MB"],
        ["兼容性", "Android最低版本", "minSdk=24 (Android 7.0)"],
        ["兼容性", "ABI架构", "arm64-v8a, armeabi-v7a"],
        ["精度-人脸检测", "正脸检测召回率", ">= 90%"],
        ["精度-跟踪", "NCC跟踪命中率(无遮挡)", ">= 80%"],
        ["存储", "Haar级联模型文件大小", "< 5MB (打包在assets中)"],
        ["存储", "人脸特征数据库", "本地存储，< 10MB"],
    ]
    story.append(make_table(nonfunc_data, col_widths=[1.1*inch, 2.8*inch, 2.3*inch]))

    story.append(Paragraph(normalize_text("2.3 输入输出规格"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "模块的输入来源包括：(1) 项目assets目录下的静态图像 app/src/main/assets/IMG_20260821_190758.jpg；"
        "(2) 项目assets目录下的测试视频 app/src/main/assets/video.mp4；"
        "(3) 用户通过相机实时采集的预览帧（可选扩展）。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "输出包括：(1) 标注了检测结果的Bitmap图像（检测框、关键点、跟踪轨迹叠加在原图上）；"
        "(2) 视频逐帧检测结果列表，每帧包含检测对象列表（类别、置信度、外接框坐标）；"
        "(3) 结构化分析报告（视频摘要、镜头列表、关键帧列表）；"
        "(4) 人脸特征数据库（特征向量+标签）。"
    ), styles['body']))

    # CH3
    story.append(Paragraph(normalize_text("第三章 实现原理详解"), styles['h1']))
    story.append(h1_divider())

    story.append(Paragraph(normalize_text("3.1 视频识别原理"), styles['h2']))
    story.append(Paragraph(normalize_text("3.1.1 运动估计与块匹配算法（BMA）"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "运动估计是视频压缩编码的核心技术（教材第5章5.3.3节），也是视频运动分析的基础。"
        "其基本原理是利用视频序列相邻帧间极强的时间相关性，通过估计运动物体的位移来减少时间域冗余。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>块匹配算法（BMA）</b>是最常用的运动估计方法。其核心假设是：同一子块内所有像素做相同平移运动。"
        "算法将当前帧分割为M x N的子块（如16x16），在前一帧的搜索区域内寻找最佳匹配块，"
        "匹配块与前块的相对位移即为运动矢量（Motion Vector, MV）。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>匹配准则：</b><br/>"
        "SAD (Sum of Absolute Differences): SAD = Sum|I(t,x,y) - I(t-1,x+dx,y+dy)|<br/>"
        "NCC (Normalized Cross Correlation): NCC = Sum(I1*I2)/sqrt(Sum(I1^2)*Sum(I2^2))<br/>"
        "MSE (Mean Squared Error): MSE = Sum(I1-I2)^2 / (M*N)"
    ), styles['code']))
    story.append(Paragraph(normalize_text(
        "<b>搜索策略：</b>教材第5章介绍了三种搜索策略："
        "(1) 全搜索（Exhaustive Search）精度最高但计算量大；"
        "(2) 三步搜索法（3-Step Search）：步长依次减半，从最大搜索范围开始逐步缩小；"
        "(3) 分级搜索（Multiscale Search）：先在低分辨率上全搜索（粗搜索），"
        "再以结果作为高分辨率细搜索的起始点。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>H.264/AVC运动估计增强（教材第6章6.3节）：</b>"
        "H.264引入了块大小可变的运动补偿（1个宏块可含1-16个运动矢量）、"
        "高精度亚像素运动估计（亮度1/4像素精度，色度1/8像素精度）、"
        "多参考帧运动补偿预测（最多5个参考帧）。这些技术可应用于本模块的多精度目标跟踪。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("3.1.2 光流法"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "光流（Optical Flow）是图像中像素的运动模式，描述了相邻帧间像素的位移矢量场（教材第9章）。"
        "光流法计算相邻帧之间的光流场，可得到像素级精确的运动信息，但计算复杂度较高。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>Horn-Schunck算法：</b>基于亮度恒定假设和平滑约束的全局光流方法。"
        "亮度恒定方程：I(x+u, y+v, t+1) = I(x, y, t)，泰勒展开得："
        "I_x*u + I_y*v + I_t = 0，其中I_x, I_y为空间梯度，I_t为时间梯度，(u,v)为光流矢量。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>Lucas-Kanade算法：</b>基于局部窗口的稀疏光流方法，"
        "假设窗口内像素具有相同运动，通过最小二乘法求解运动矢量。"
        "计算量小于全局方法，适合实时应用。"
        "OpenCV中的金字塔Lucas-Kanade算法（cv::calcOpticalFlowPyrLK）采用多分辨率金字塔提升大位移鲁棒性。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>Farneback算法：</b>基于多项式展开的稠密光流方法，"
        "OpenCV中通过cv::calcOpticalFlowFarneback()实现，"
        "可计算每个像素的完整运动矢量场，适合运动目标检测和可视化。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("3.1.3 背景建模与前景提取"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "背景建模是运动目标检测的关键预处理步骤。基本思路是建立背景模型，"
        "将当前帧与背景模型比较，差异较大的像素判定为前景（运动目标）。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>1. 帧差法（已有实现）：</b>相邻帧差分二值化，统计变化像素占比与外接框。"
        "优点是计算简单快速，缺点是无法处理静止目标和背景渐变。"
        "公式：D(x,y) = |I(t,x,y) - I(t-1,x,y)|，若D > T则为前景。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>2. 混合高斯模型（MOG2）：</b>对每个像素建立K个高斯分布（通常K=3-5），"
        "自适应更新背景模型。能够处理缓慢光照变化和周期性运动背景（如树叶摇摆）。"
        "像素值概率密度：P(x) = Sum(w_i * N(x, mu_i, sigma_i^2))。"
        "OpenCV通过cv::createBackgroundSubtractorMOG2()实现。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>3. KNN背景减除：</b>对每个像素维护最近的N个样本值，"
        "当前像素与最近邻的距离超过阈值则判定为前景。"
        "OpenCV通过cv::createBackgroundSubtractorKNN()实现，对非周期性背景运动有更好适应性。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>4. 形态学后处理：</b>对前景掩码进行开运算去除噪声、闭运算填充空洞、"
        "腐蚀膨胀去除孤立点，提取连通域外接矩形作为目标区域。"
        "利用imageCVdeal模块已有的形态学算法实现。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("3.1.4 目标跟踪算法"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "目标跟踪是在视频序列中持续定位特定目标的过程。本模块实现以下跟踪策略："
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>1. NCC模板跟踪（已有基础，需增强）：</b>"
        "首帧中央取模板（48x48），后续帧在上一帧位置邻域（半径32像素）内用NCC搜索最佳匹配。"
        "增强方向：多尺度金字塔搜索、旋转不变性、丢失检测与重定位。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>2. 光流跟踪：</b>利用Lucas-Kanade金字塔光流法跟踪特征点，"
        "在首帧检测Shi-Tomasi角点（cv::goodFeaturesToTrack），"
        "后续帧用cv::calcOpticalFlowPyrLK()跟踪。"
        "优势：可跟踪多个稀疏特征点，对部分遮挡有鲁棒性。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>3. Meanshift/Camshift跟踪：</b>"
        "Meanshift基于颜色直方图反向投影的概率密度梯度上升搜索目标区域；"
        "Camshift在Meanshift基础上自适应调整窗口大小和旋转角度。"
        "适合跟踪颜色特征明显的目标。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>4. 多目标跟踪：</b>结合检测器（Haar/HOG）和跟踪器（NCC/光流），"
        "实现检测-跟踪融合框架。首帧检测初始化目标，后续帧用跟踪器维持，"
        "定期用检测器校正和发现新目标。使用匈牙利匹配或IoU关联管理目标ID。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("3.1.5 视频结构化分析（教材第10章）"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "视频结构化是将视频从低层帧序列组织为高层语义结构的过程，"
        "分层结构为：视频 -> 场景(Scene) -> 镜头(Shot) -> 关键帧(Key Frame) -> 帧(Frame)。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>镜头边界检测：</b>计算相邻帧间距离（直方图差异、像素差异），"
        "采用双阈值检测法：帧间距离 > TC判定为镜头突变；"
        "TG < 帧间距离 < TC判定为镜头渐变。"
        "可利用contentsearch模块已有的ImageFeatures HSV颜色直方图计算帧间距离。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>关键帧提取：</b>基于颜色特征的方法——比较当前帧与上一个关键帧的颜色特征，"
        "变化超过阈值则作为新关键帧。也可采用帧平均法（取与镜头内所有帧平均值最接近的帧）。"
        "可复用contentsearch模块的ImageSearchEngine特征提取能力。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>镜头聚类（场景检测）：</b>基于关键帧特征将镜头聚集成场景，"
        "采用K-Means或层次聚类算法，反映高层语义。"
    ), styles['body']))

    # 3.2
    story.append(Paragraph(normalize_text("3.2 物体识别原理"), styles['h2']))
    story.append(Paragraph(normalize_text("3.2.1 特征提取方法"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "特征提取是物体识别的核心环节（教材第11章11.1节），"
        "识别系统框架为：图像获取 -> 预处理 -> 特征提取 -> 分类决策。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>1. Haar-like特征：</b>由Paul Viola和Michael Jones于2001年提出（教材第11章提及），"
        "是基于矩形黑白区域亮度差的简单特征。每个特征由2-3个矩形组成，"
        "特征值 = 白色矩形像素和 - 黑色矩形像素和。"
        "利用积分图（Integral Image）可在O(1)时间计算任意矩形区域的像素和。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "Haar特征类型包括：边缘特征（2矩形）、线特征（3矩形）、"
        "对角线特征（4矩形）、中心特征等。"
        "通过AdaBoost算法从海量特征中筛选出最具分类能力的少量关键特征，"
        "构建级联分类器（Cascade Classifier）实现快速检测。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>2. HOG特征（梯度方向直方图）：</b>"
        "计算每个像素的梯度幅值和方向，将图像划分为小单元格（Cell，如8x8），"
        "在每个Cell内统计梯度方向直方图（通常9个方向bin，0-180度），"
        "将相邻Cell组成块（Block，如2x2 Cells），块内归一化，"
        "最终将所有块的直方图串联为特征向量。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "HOG特征对光照变化和局部几何形变有较强鲁棒性，"
        "配合SVM分类器广泛应用于行人检测。"
        "OpenCV通过cv::HOGDescriptor实现，内置预训练的行人检测模型。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>3. SIFT特征（尺度不变特征变换）：</b>"
        "通过DoG（Difference of Gaussian）金字塔检测尺度空间极值点，"
        "计算主方向（基于局部梯度方向直方图），"
        "生成128维特征描述子（4x4x8=128）。"
        "具有尺度、旋转、光照不变性，适合特征点匹配识别。"
        "OpenCV 4.x通过cv::SIFT::create()和cv::SIFT::detectAndCompute()实现。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>4. LBP特征（局部二值模式）：</b>"
        "对每个像素，以其为中心与周围3x3邻域8个像素比较，"
        "大于等于中心值记1，否则记0，得到8位二进制编码（0-255）。"
        "统计LBP编码直方图作为纹理特征。"
        "具有光照不变性，计算简单，广泛应用于人脸识别。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("3.2.2 分类器设计"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "<b>1. 支持向量机（SVM）（教材第11章11.2节）：</b>"
        "SVM寻找最优超平面 f(x) = w^T x + b 将不同类别分开，"
        "使支持向量（距超平面最近的样本）到超平面的间隔 gamma = 2/||w|| 最大。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "原始优化问题：min (1/2)||w||^2  s.t. y_i(w^T x_i + b) >= 1<br/>"
        "对偶问题：max Sum(a_i) - (1/2)Sum_i Sum_j a_i a_j y_i y_j x_i^T x_j<br/>"
        "核函数：K(x_i, x_j) = phi(x_i)^T phi(x_j)，将非线性可分问题映射到高维空间。<br/>"
        "常用核函数：线性核、多项式核、RBF核 K(x,y) = exp(-||x-y||^2 / (2*sigma^2))、Sigmoid核"
    ), styles['code']))
    story.append(Paragraph(normalize_text(
        "经验风险最小化（ERM）：用训练样本平均损失最小化代替期望风险最小化。"
        "结构风险最小化（SRM）：在经验风险基础上加正则化项防止过拟合：R_srm = R_emp + lambda*J(f)。"
        "SVM等价于SRM的一种实现。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>2. AdaBoost级联分类器：</b>"
        "用于Haar特征人脸/目标检测。级联结构由多个强分类器串联组成，"
        "每个强分类器由多个弱分类器（决策桩）通过AdaBoost加权组合。"
        "前级使用少量特征快速排除大部分负样本，后级使用更多特征精细判别。"
        "OpenCV通过cv::CascadeClassifier实现，加载XML格式的训练模型。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>3. 卷积神经网络CNN（教材第11章11.4节）：</b>"
        "CNN基于三个核心概念：局部感受野（LRF，神经元只连接局部区域）、"
        "权值共享（同一卷积核所有位置使用相同权重，大幅减少参数）、"
        "池化/下采样（最大值池化或平均值池化，减少数据量并增加平移不变性）。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "CNN基本结构：输入层 -> 卷积层（特征提取） -> 池化层（下采样） -> "
        "全连接层（分类） -> 输出层。卷积操作：y_ij = Sum_m Sum_n(x_{i+m-1,j+n-1} * w_{mn})。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "教材以LeNet-5为例介绍了经典CNN架构：C1卷积层(6个5x5卷积核) -> "
        "S2下采样(6个14x14) -> C3卷积(16个5x5核) -> S4下采样 -> "
        "C5卷积(120个全连接) -> F6全连接(84个) -> 输出层(10个数字类别)。"
        "考虑到项目不使用深度学习框架，CNN部分作为理论参考，实际实现以传统方法为主。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("3.2.3 模板匹配（已有基础，需增强）"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "项目已有NccMatcher实现归一化互相关模板匹配，"
        "采用粗到精金字塔搜索策略（1/4分辨率全图搜索 -> 全分辨率局部精化）。"
        "NCC公式：NCC = Sum(I1*I2) / sqrt(Sum(I1^2) * Sum(I2^2))，"
        "对线性光照变化不变。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "增强方向：(1) 多尺度匹配——在不同缩放因子下搜索，适应目标尺寸变化；"
        "(2) 旋转匹配——在多个角度下搜索，适应目标旋转；"
        "(3) 多模板匹配——支持多个模板同时匹配，识别不同物体。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("3.2.4 形状识别（已有基础，需增强）"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "项目已有ShapeRecognizer基于Hu不变矩实现5类形状（圆/矩/三角/星/椭圆）识别。"
        "Hu不变矩具有平移、旋转、尺度不变性，7个矩经过对数变换后用于最近邻分类。"
        "增强方向：(1) 增加更多形状类别（如六边形、十字、箭头等）；"
        "(2) 使用SVM替代最近邻分类器提升精度；"
        "(3) 结合轮廓的傅里叶描述子增强形状区分能力。"
    ), styles['body']))

    # 3.3
    story.append(Paragraph(normalize_text("3.3 人脸识别原理"), styles['h2']))
    story.append(Paragraph(normalize_text("3.3.1 Haar级联分类器人脸检测"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "Viola-Jones人脸检测框架（2001年）是第一个实现实时人脸检测的方法（教材第11章提及），"
        "由三个核心组件构成："
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>1. 积分图（Integral Image）：</b>快速计算矩形区域像素和的辅助图像。"
        "积分图II(x,y) = Sum_{x'&lt;x, y'&lt;y} I(x', y')，"
        "任意矩形区域D的像素和只需4次积分图查表：Sum(D) = II(左上) + II(右下) - II(右上) - II(左下)。"
        "这使得Haar特征计算复杂度从O(M*N)降到O(1)。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>2. AdaBoost特征选择与分类器训练：</b>"
        "从海量Haar特征中筛选少量最具分类能力的特征构建弱分类器（每个弱分类器对应一个特征），"
        "通过AdaBoost算法迭代加权组合弱分类器为强分类器。"
        "每轮迭代增加被错分样本的权重，减少正确分类样本的权重。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>3. 级联结构（Cascade）：</b>"
        "多个强分类器串联，前级使用少量特征快速排除大部分非人脸区域（负样本），"
        "后级使用更多特征精细判别。"
        "通常级联有20-25级，前几级使用1-2个特征可排除约50%的负样本。"
        "检测时对图像多尺度滑动窗口扫描，每个尺度的每个窗口依次通过级联分类器。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "OpenCV通过cv::CascadeClassifier实现，"
        "加载预训练的XML模型文件（如haarcascade_frontalface_default.xml）。"
        "关键方法：detectMultiScale(image, objects, scaleFactor, minNeighbors, flags, minSize, maxSize)。"
        "scaleFactor控制多尺度缩放比例（通常1.1-1.3），"
        "minNeighbors控制NMS去重的邻域重叠阈值。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("3.3.2 特征脸方法（Eigenface / PCA）"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "特征脸方法基于主成分分析（PCA），将高维人脸图像投影到低维特征子空间。步骤如下："
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "1. 收集N张对齐的人脸训练图像，每张MxN像素展开为M*N维向量；<br/>"
        "2. 计算平均脸 mu = (1/N) * Sum(x_i)；<br/>"
        "3. 中心化：phi_i = x_i - mu；<br/>"
        "4. 计算协方差矩阵 C = A * A^T（A为Phi矩阵），或用奇异值分解降维；<br/>"
        "5. 求C的特征值和特征向量，取前k个最大特征值对应的特征向量构成特征脸空间U；<br/>"
        "6. 人脸特征向量：omega = U^T * (x - mu)，为k维低维表示；<br/>"
        "7. 识别：计算查询人脸特征与数据库中人脸特征的最近邻距离。"
    ), styles['code']))

    story.append(Paragraph(normalize_text("3.3.3 LBP特征人脸识别"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "LBP（局部二值模式）是简单有效的纹理特征描述子，在人脸识别中应用广泛。基本步骤："
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "1. 对人脸图像每个像素计算3x3邻域LBP编码（8位二进制）；<br/>"
        "2. 将人脸图像划分为m x n个子区域（如7x7=49个子区域）；<br/>"
        "3. 在每个子区域内统计LBP直方图（256维）；<br/>"
        "4. 串联所有子区域直方图得到最终特征向量（49*256=12544维）；<br/>"
        "5. 用卡方距离或直方图交叉距离度量人脸相似度。"
    ), styles['code']))
    story.append(Paragraph(normalize_text(
        "LBP+直方图方法对光照变化有较强鲁棒性，且计算简单无需训练。"
        "统一LBP（Uniform LBP）将256种模式映射为59种，进一步降维。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("3.3.4 人脸识别完整流程"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "完整的人脸识别系统包括检测、对齐、特征提取、匹配四个阶段："
    ), styles['body']))
    flow_code = """
digraph {
    rankdir=LR
    node [shape=box style="rounded,filled" fontname="Noto Sans CJK SC" fontsize=14 margin="0.30,0.20"]
    edge [fontname="Noto Sans CJK SC" fontsize=12 penwidth=1.5]
    input [label="输入图像/视频帧" fillcolor="#E8F4FD"]
    detect [label="人脸检测\\n(Haar级联)" fillcolor="#BBDEFB"]
    align [label="人脸对齐\\n(眼睛对齐+仿射变换)" fillcolor="#C8E6C9"]
    extract [label="特征提取\\n(LBP/PCA)" fillcolor="#FFE0B2"]
    match [label="特征匹配\\n(最近邻/卡方距离)" fillcolor="#E1BEE7"]
    output [label="识别结果\\n(身份+置信度)" fillcolor="#FFCDD2"]
    input -> detect -> align -> extract -> match -> output
}
"""
    render_graphviz_to_png(flow_code, "/tmp/face_pipeline.png")
    add_figure(story, "/tmp/face_pipeline.png", "图 3-1: 人脸识别完整流程", styles)

    story.append(Paragraph(normalize_text(
        "<b>1. 人脸检测：</b>使用Haar级联分类器在图像中定位人脸区域，输出人脸外接矩形框。"
        "对视频帧逐帧执行检测，可结合跟踪减少重复检测开销。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>2. 人脸对齐：</b>利用眼睛关键点计算旋转角度，"
        "通过仿射变换将人脸归一化为正面朝向、固定尺寸（如100x100），消除姿态差异。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>3. 特征提取：</b>对对齐后的人脸提取LBP直方图特征或PCA特征脸特征，"
        "生成固定维度的特征向量。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>4. 特征匹配：</b>将提取的特征向量与数据库中已知人脸特征比对，"
        "使用卡方距离或欧氏距离度量，距离最小者且低于阈值则判定为匹配。"
        "未匹配则标记为未知人脸。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("3.4 关键数学公式汇总"), styles['h2']))
    formula_data = [
        ["编号", "公式", "出处", "说明"],
        ["F-01", "SAD = Sum|x1-x2|", "Ch.5", "块匹配绝对误差和"],
        ["F-02", "NCC = Sum(I1*I2)/sqrt(Sum(I1^2)*Sum(I2^2))", "已有代码", "归一化互相关"],
        ["F-03", "I_x*u + I_y*v + I_t = 0", "Ch.9", "光流亮度恒定方程"],
        ["F-04", "P(x) = Sum(w_i*N(mu_i,sigma_i^2))", "Ch.9", "混合高斯背景模型"],
        ["F-05", "f(x) = w^T*x + b", "Ch.11", "SVM超平面"],
        ["F-06", "gamma = 2/||w||", "Ch.11", "SVM分类间隔"],
        ["F-07", "min(1/2)||w||^2 s.t. y_i(w^T*x_i+b)>=1", "Ch.11", "SVM原始优化"],
        ["F-08", "K(x,y)=exp(-||x-y||^2/(2*sigma^2))", "Ch.11", "RBF核函数"],
        ["F-09", "R_srm = R_emp + lambda*J(f)", "Ch.11", "结构风险最小化"],
        ["F-10", "II(x,y)=Sum(I(x',y'))", "Ch.11", "积分图定义"],
        ["F-11", "y_ij=Sum_m Sum_n(x*w_mn)", "Ch.11", "卷积操作"],
        ["F-12", "f(x)=max(0,x)", "Ch.11", "ReLU激活函数"],
        ["F-13", "omega = U^T*(x-mu)", "PCA", "特征脸投影"],
        ["F-14", "LBP = Sum(2^i * s(p_i-p_c))", "纹理", "LBP编码"],
    ]
    story.append(make_table(formula_data, col_widths=[0.5*inch, 2.8*inch, 0.7*inch, 2.3*inch]))

    # CH4
    story.append(Paragraph(normalize_text("第四章 代码实现架构"), styles['h1']))
    story.append(h1_divider())

    story.append(Paragraph(normalize_text("4.1 模块整体架构"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "在现有imagerecognition模块基础上扩展，新增三个子包："
        "video（视频识别）、object（物体识别）、face（人脸识别）。"
        "复用imageCVdeal的OpenCV JNI能力和contentsearch的特征提取能力。"
    ), styles['body']))
    arch_code = """
digraph {
    rankdir=TB
    nodesep=0.35
    ranksep=0.5
    node [shape=box style="rounded,filled" fontname="Noto Sans CJK SC" fontsize=12 margin="0.18,0.14"]
    subgraph cluster_app {
        label="app (UI层)"
        style="rounded,dashed"
        fontname="Noto Sans CJK SC"
        fontsize=13
        MainActivity [label="MainActivity+FFViewModel" fillcolor="#E8F4FD"]
        Fragments [label="Fragment层(菜单/检测/识别页面)" fillcolor="#E8F4FD"]
    }
    subgraph cluster_ir {
        label="imagerecognition (识别模块)"
        style="rounded,dashed"
        fontname="Noto Sans CJK SC"
        fontsize=13
        VideoPipeline [label="VideoRecognitionPipeline(已有,增强)" fillcolor="#BBDEFB"]
        MotionDetector [label="MotionDetector(新增)" fillcolor="#C8E6C9"]
        ObjectTracker [label="ObjectTracker(新增)" fillcolor="#C8E6C9"]
        VideoStructurer [label="VideoStructurer(新增)" fillcolor="#C8E6C9"]
        HaarDetector [label="HaarDetector(新增)" fillcolor="#FFE0B2"]
        HogDetector [label="HogDetector(新增)" fillcolor="#FFE0B2"]
        FeatureMatcher [label="FeatureMatcher(新增)" fillcolor="#FFE0B2"]
        FaceDetector [label="FaceDetector(新增)" fillcolor="#E1BEE7"]
        FaceRecognizer [label="FaceRecognizer(新增)" fillcolor="#E1BEE7"]
        FaceDatabase [label="FaceDatabase(新增)" fillcolor="#E1BEE7"]
        NccMatcher [label="NccMatcher(已有)" fillcolor="#FFF9C4"]
        ShapeRecognizer [label="ShapeRecognizer(已有)" fillcolor="#FFF9C4"]
    }
    subgraph cluster_cv {
        label="imageCVdeal (OpenCV JNI)"
        style="rounded,dashed"
        fontname="Noto Sans CJK SC"
        fontsize=13
        OpenCVJNI [label="OpencvDealJni+native-lib.cpp" fillcolor="#F5F5F5"]
    }
    subgraph cluster_cs {
        label="contentsearch (内容检索)"
        style="rounded,dashed"
        fontname="Noto Sans CJK SC"
        fontsize=13
        ImageFeatures [label="ImageFeatures(HSV/Texture)" fillcolor="#F5F5F5"]
    }
    MainActivity -> Fragments
    Fragments -> VideoPipeline
    Fragments -> HaarDetector
    Fragments -> FaceDetector
    VideoPipeline -> MotionDetector
    VideoPipeline -> ObjectTracker
    VideoPipeline -> VideoStructurer
    ObjectTracker -> NccMatcher
    VideoStructurer -> ImageFeatures
    HaarDetector -> OpenCVJNI
    HogDetector -> OpenCVJNI
    FaceDetector -> OpenCVJNI
    FaceRecognizer -> OpenCVJNI
}
"""
    render_graphviz_to_png(arch_code, "/tmp/architecture.png")
    add_figure(story, "/tmp/architecture.png", "图 4-1: 模块整体架构图", styles)

    story.append(Paragraph(normalize_text("4.2 新增包与文件结构"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "在imagerecognition模块中新增以下文件结构："
    ), styles['body']))
    tree_text = """imagerecognition/src/main/java/com/wangyao/imagerecognition/
  core/
    NccMatcher.kt              (已有,增强多尺度/旋转)
    ShapeRecognizer.kt          (已有,增强形状类别)
    MotionDetector.kt           (新增: MOG2/KNN/帧差背景减除)
    ObjectTracker.kt            (新增: NCC/光流/Meanshift跟踪)
    VideoStructurer.kt           (新增: 镜头检测/关键帧/场景聚类)
  object/
    HaarDetector.kt             (新增: Haar级联目标检测)
    HogDetector.kt              (新增: HOG+SVM行人检测)
    FeatureMatcher.kt           (新增: SIFT/ORB特征点匹配)
    DetectionResult.kt          (新增: 检测结果数据类)
  face/
    FaceDetector.kt             (新增: Haar人脸检测+关键点)
    FaceRecognizer.kt           (新增: LBP/PCA人脸识别)
    FaceDatabase.kt             (新增: 人脸特征数据库管理)
    FaceAlignment.kt            (新增: 人脸对齐)
  video/
    VideoRecognitionPipeline.kt (已有,增强多目标检测跟踪)
    VideoObjectDetector.kt      (新增: 视频逐帧物体检测)
    MultiObjectTracker.kt       (新增: 多目标跟踪管理)"""
    story.append(Paragraph(normalize_text(tree_text), styles['code']))

    story.append(Paragraph(normalize_text("4.3 核心类设计"), styles['h2']))

    # 4.3.1
    story.append(Paragraph(normalize_text("4.3.1 MotionDetector（运动检测器）"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "职责：基于背景建模和帧差法检测视频中的运动目标，输出前景掩码和目标外接框。"
    ), styles['body']))
    code_1 = """// MotionDetector.kt
package com.wangyao.imagerecognition.core

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import org.opencv.video.BackgroundSubtractor

class MotionDetector(
    private val method: Method = Method.MOG2,
    private val history: Int = 500,
    private val varThreshold: Double = 16.0
) {
    enum class Method { FRAME_DIFF, MOG2, KNN }
    private var subtractor: BackgroundSubtractor? = null
    private var prevGray: Mat? = null

    init {
        when (method) {
            Method.MOG2 -> subtractor = Video.createBackgroundSubtractorMOG2(
                history, varThreshold, true)
            Method.KNN -> subtractor = Video.createBackgroundSubtractorKNN(
                history, varThreshold, true)
            Method.FRAME_DIFF -> { }
        }
    }

    data class Detection(
        val motionRatio: Double,
        val boxes: List<Rect>,
        val foregroundMask: ByteArray
    )

    fun detect(gray: Mat, width: Int, height: Int): Detection {
        val mask = Mat()
        when (method) {
            Method.FRAME_DIFF -> {
                if (prevGray != null) {
                    Core.absdiff(gray, prevGray!!, mask)
                    Imgproc.threshold(mask, mask, 25.0, 255.0,
                        Imgproc.THRESH_BINARY)
                } else {
                    mask = Mat.zeros(gray.size(), gray.type())
                }
                prevGray = gray.clone()
            }
            Method.MOG2, Method.KNN -> {
                subtractor?.apply(gray, mask)
            }
        }
        // 形态学后处理
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        // 连通域分析
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(),
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val minArea = width * height * 0.005
        val boxes = contours
            .map { Imgproc.boundingRect(it) }
            .filter { it.area() > minArea }
            .toList()
        val motionPixels = Core.countNonZero(mask)
        val motionRatio = motionPixels.toDouble() / (width * height)
        return Detection(motionRatio, boxes, mask.toArray())
    }
    fun release() {
        subtractor?.clear()
        prevGray?.release()
    }
}"""
    story.append(Paragraph(normalize_text(code_1), styles['code']))

    # 4.3.2
    story.append(Paragraph(normalize_text("4.3.2 ObjectTracker（目标跟踪器）"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "职责：在视频序列中持续定位特定目标，支持NCC模板跟踪和光流跟踪两种模式。"
    ), styles['body']))
    code_2 = """// ObjectTracker.kt
package com.wangyao.imagerecognition.core

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

class ObjectTracker(
    private val method: TrackMethod = TrackMethod.NCC,
    private val searchRadius: Int = 32,
    private val templateSize: Int = 48,
    private val lostThreshold: Double = 0.5
) {
    enum class TrackMethod { NCC, OPTICAL_FLOW }

    data class TrackResult(
        val x: Double, val y: Double,
        val score: Double,
        val isLost: Boolean,
        val trajectory: List<Point>
    )
    private var template: Mat? = null
    private var prevGray: Mat? = null
    private var prevPoints: MatOfPoint2f? = null
    private var centerX: Double = 0.0
    private var centerY: Double = 0.0
    private val trajectory = mutableListOf<Point>()
    private var lostCount: Int = 0

    fun init(gray: Mat, startX: Int, startY: Int) {
        centerX = startX.toDouble()
        centerY = startY.toDouble()
        when (method) {
            TrackMethod.NCC -> {
                val half = templateSize / 2
                template = Mat(gray, Rect(
                    startX - half, startY - half,
                    templateSize, templateSize)).clone()
            }
            TrackMethod.OPTICAL_FLOW -> {
                val corners = MatOfPoint2f()
                Imgproc.goodFeaturesToTrack(gray, corners, 50, 0.01, 10.0)
                prevPoints = corners
                prevGray = gray.clone()
            }
        }
        trajectory.add(Point(centerX, centerY))
    }

    fun track(gray: Mat): TrackResult {
        return when (method) {
            TrackMethod.NCC -> trackNCC(gray)
            TrackMethod.OPTICAL_FLOW -> trackOpticalFlow(gray)
        }
    }

    private fun trackNCC(gray: Mat): TrackResult {
        val tpl = template ?: return TrackResult(
            centerX, centerY, 0.0, true, trajectory)
        val sx = (centerX - searchRadius).toInt().coerceAtLeast(0)
        val sy = (centerY - searchRadius).toInt().coerceAtLeast(0)
        val sw = minOf(searchRadius * 2 + templateSize, gray.cols() - sx)
        val sh = minOf(searchRadius * 2 + templateSize, gray.rows() - sy)
        if (sw <= 0 || sh <= 0)
            return TrackResult(centerX, centerY, 0.0, true, trajectory)
        val searchRegion = Mat(gray, Rect(sx, sy, sw, sh))
        val result = Mat()
        Imgproc.matchTemplate(searchRegion, tpl, result,
            Imgproc.TM_CCOEFF_NORMED)
        val minMax = Core.minMaxLoc(result)
        val score = minMax.maxVal
        val maxLoc = minMax.maxLoc
        if (score > lostThreshold) {
            centerX = sx + maxLoc.x + templateSize / 2.0
            centerY = sy + maxLoc.y + templateSize / 2.0
            lostCount = 0
        } else { lostCount++ }
        trajectory.add(Point(centerX, centerY))
        return TrackResult(centerX, centerY, score,
            score < lostThreshold, trajectory)
    }

    private fun trackOpticalFlow(gray: Mat): TrackResult {
        val prev = prevGray ?: return TrackResult(
            centerX, centerY, 0.0, true, trajectory)
        val prevPts = prevPoints ?: return TrackResult(
            centerX, centerY, 0.0, true, trajectory)
        val nextPts = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        Video.calcOpticalFlowPyrLK(prev, gray, prevPts, nextPts, status, err)
        val statusArr = status.toArray()
        val nextArr = nextPts.toArray()
        var sumX = 0.0; var sumY = 0.0; var count = 0
        for (i in statusArr.indices) {
            if (statusArr[i] == 1.toByte()) {
                sumX += nextArr[i].x; sumY += nextArr[i].y; count++
            }
        }
        if (count > 0) {
            centerX = sumX / count; centerY = sumY / count
            lostCount = 0; prevPoints = nextPts; prevGray = gray.clone()
        } else { lostCount++ }
        trajectory.add(Point(centerX, centerY))
        val score = if (count > 0) count.toDouble() / statusArr.size else 0.0
        return TrackResult(centerX, centerY, score, count == 0, trajectory)
    }
}"""
    story.append(Paragraph(normalize_text(code_2), styles['code']))

    # 4.3.3
    story.append(Paragraph(normalize_text("4.3.3 HaarDetector（Haar级联检测器）"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "职责：基于Haar级联分类器实现通用目标检测（人脸/眼睛/车身等），"
        "支持加载OpenCV预训练XML模型和自定义模型。"
    ), styles['body']))
    code_3 = """// HaarDetector.kt
package com.wangyao.imagerecognition.object

import org.opencv.core.*
import org.opencv.objdetect.CascadeClassifier

class HaarDetector(
    private val cascadePath: String,
    private val scaleFactor: Double = 1.1,
    private val minNeighbors: Int = 3,
    private val minSize: Size = Size(30.0, 30.0),
    private val maxSize: Size = Size()
) {
    private val cascade: CascadeClassifier = CascadeClassifier()

    data class Detection(
        val x: Double, val y: Double,
        val width: Double, val height: Double,
        val confidence: Int
    )

    init {
        if (!cascade.load(cascadePath))
            throw IllegalArgumentException("Failed to load: $cascadePath")
    }

    fun detect(gray: Mat): List<Detection> {
        val objects = MatOfRect()
        cascade.detectMultiScale(gray, objects, scaleFactor,
            minNeighbors, 0, minSize, maxSize)
        return objects.toList().map { rect ->
            Detection(rect.x.toDouble(), rect.y.toDouble(),
                rect.width.toDouble(), rect.height.toDouble(),
                minNeighbors)
        }
    }

    fun detectNested(gray: Mat, nestedDetector: HaarDetector,
        roiPadding: Double = 0.1
    ): Map<Detection, List<Detection>> {
        val outer = detect(gray)
        val result = mutableMapOf<Detection, List<Detection>>()
        for (det in outer) {
            val padW = det.width * roiPadding
            val padH = det.height * roiPadding
            val roi = Rect(
                maxOf(0, (det.x + padW * 0.5).toInt()),
                maxOf(0, (det.y + padH * 0.5).toInt()),
                minOf(gray.cols(), (det.width - padW).toInt()),
                minOf(gray.rows(), (det.height - padH * 0.5).toInt()))
            if (roi.width > 0 && roi.height > 0) {
                val roiMat = Mat(gray, roi)
                val nested = nestedDetector.detect(roiMat)
                result[det] = nested.map {
                    it.copy(x = it.x + roi.x, y = it.y + roi.y)
                }
            }
        }
        return result
    }
}"""
    story.append(Paragraph(normalize_text(code_3), styles['code']))

    # 4.3.4
    story.append(Paragraph(normalize_text("4.3.4 HogDetector（HOG行人检测器）"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "职责：基于HOG特征描述子和SVM分类器实现行人检测。"
        "使用OpenCV内置的预训练HOG行人检测模型。"
    ), styles['body']))
    code_4 = """// HogDetector.kt
package com.wangyao.imagerecognition.object

import org.opencv.core.*
import org.opencv.objdetect.HOGDescriptor

class HogDetector(
    private var hitThreshold: Double = 0.0,
    private var winStride: Size = Size(8.0, 8.0),
    private var padding: Size = Size(4.0, 4.0),
    private var scale: Double = 1.05
) {
    private val hog: HOGDescriptor = HOGDescriptor()

    data class Detection(
        val x: Double, val y: Double,
        val width: Double, val height: Double,
        val score: Double
    )

    init {
        hog.setSVMDetector(HOGDescriptor.getDefaultPeopleDetector())
    }

    fun detect(gray: Mat): List<Detection> {
        val found = MatOfRect()
        val weights = MatOfDouble()
        hog.detectMultiScale(gray, found, weights,
            hitThreshold, winStride, padding, scale)
        val rects = found.toList()
        val scores = weights.toList()
        return rects.mapIndexed { i, rect ->
            Detection(rect.x.toDouble(), rect.y.toDouble(),
                rect.width.toDouble(), rect.height.toDouble(),
                if (i < scores.size) scores[i] else 0.0)
        }.sortedByDescending { it.score }
    }
}"""
    story.append(Paragraph(normalize_text(code_4), styles['code']))

    # 4.3.5
    story.append(Paragraph(normalize_text("4.3.5 FaceDetector & FaceRecognizer（人脸检测与识别）"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "FaceDetector职责：基于Haar级联分类器检测人脸位置，支持眼睛关键点定位。"
        "FaceRecognizer职责：基于LBP直方图特征和PCA特征脸方法实现人脸身份识别。"
    ), styles['body']))
    code_5 = """// FaceDetector.kt
package com.wangyao.imagerecognition.face

import org.opencv.core.*
import org.opencv.objdetect.CascadeClassifier

class FaceDetector(
    private val faceCascadePath: String,
    private val eyeCascadePath: String? = null
) {
    private val faceCascade = CascadeClassifier()
    private val eyeCascade: CascadeClassifier? = eyeCascadePath?.let {
        CascadeClassifier().also { c -> c.load(it) }
    }

    data class FaceDetection(
        val face: Rect,
        val eyes: List<Rect>,
        val confidence: Double
    )

    init {
        require(faceCascade.load(faceCascadePath)) {
            "Failed to load face cascade: $faceCascadePath"
        }
    }

    fun detect(gray: Mat): List<FaceDetection> {
        val faces = MatOfRect()
        faceCascade.detectMultiScale(gray, faces, 1.3, 5,
            0, Size(30.0, 30.0), Size())
        return faces.toList().map { faceRect ->
            val eyes = if (eyeCascade != null) {
                val roi = Mat(gray, faceRect)
                val eyeRects = MatOfRect()
                eyeCascade.detectMultiScale(roi, eyeRects, 1.1, 3, 0)
                eyeRects.toList().map {
                    Rect(it.x + faceRect.x, it.y + faceRect.y,
                        it.width, it.height)
                }
            } else emptyList()
            FaceDetection(faceRect, eyes, 1.0)
        }
    }
}

// FaceRecognizer.kt
package com.wangyao.imagerecognition.face

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class FaceRecognizer(
    private val method: Method = Method.LBP,
    private val faceSize: Int = 100,
    private val gridX: Int = 7,
    private val gridY: Int = 7,
    private val numComponents: Int = 80
) {
    enum class Method { LBP, PCA_EIGENFACE }

    data class RecognitionResult(
        val label: String,
        val confidence: Double,
        val distance: Double,
        val isUnknown: Boolean
    )

    private val features = mutableListOf<Pair<String, FloatArray>>()

    fun extractFeature(alignedFace: Mat): FloatArray {
        return when (method) {
            Method.LBP -> extractLBP(alignedFace)
            Method.PCA_EIGENFACE -> extractPCA(alignedFace)
        }
    }

    private fun extractLBP(face: Mat): FloatArray {
        val lbp = Mat(face.size(), CvType.CV_8UC1)
        for (i in 1 until face.rows() - 1) {
            for (j in 1 until face.cols() - 1) {
                val center = face.get(i, j)[0]
                var code = 0
                val neighbors = arrayOf(
                    face.get(i-1,j-1)[0], face.get(i-1,j)[0],
                    face.get(i-1,j+1)[0], face.get(i,j+1)[0],
                    face.get(i+1,j+1)[0], face.get(i+1,j)[0],
                    face.get(i+1,j-1)[0], face.get(i,j-1)[0])
                for (k in neighbors.indices)
                    if (neighbors[k] >= center) code = code or (1 shl k)
                lbp.put(i, j, code.toByte())
            }
        }
        val cellW = face.cols() / gridX
        val cellH = face.rows() / gridY
        val hist = FloatArray(gridX * gridY * 59)
        for (gy in 0 until gridY) {
            for (gx in 0 until gridX) {
                val roi = Mat(lbp, Rect(
                    gx*cellW, gy*cellH, cellW, cellH))
                val histRoi = FloatArray(59)
                for (i in 0 until roi.rows()) {
                    for (j in 0 until roi.cols()) {
                        val v = roi.get(i,j)[0].toInt() and 0xFF
                        val u = uniformMap(v)
                        if (u < 59) histRoi[u]++
                    }
                }
                val norm = Math.sqrt(
                    histRoi.sumOf { it.toDouble()*it }).toFloat()
                if (norm > 0)
                    for (k in histRoi.indices) histRoi[k] /= norm
                val offset = (gy * gridX + gx) * 59
                System.arraycopy(histRoi, 0, hist, offset, 59)
            }
        }
        return hist
    }

    private val uniformMap = IntArray(256) { i ->
        if (countTransitions(i) <= 2) i else 58
    }
    private fun countTransitions(v: Int): Int {
        var count = 0
        for (i in 0 until 8) {
            val b1 = (v shr i) and 1
            val b2 = (v shr ((i+1)%8)) and 1
            if (b1 != b2) count++
        }
        return count
    }

    private fun extractPCA(face: Mat): FloatArray {
        val resized = Mat()
        Imgproc.resize(face, resized,
            Size(faceSize.toDouble(), faceSize.toDouble()))
        val vector = FloatArray(resized.rows()*resized.cols())
        val data = ByteArray(vector.size)
        resized.get(0, 0, data)
        for (i in data.indices)
            vector[i] = (data[i].toInt() and 0xFF).toFloat()
        return vector
    }

    fun enroll(label: String, faceMat: Mat) {
        features.add(label to extractFeature(faceMat))
    }

    fun recognize(alignedFace: Mat): RecognitionResult {
        val query = extractFeature(alignedFace)
        var bestLabel = "Unknown"
        var bestDist = Double.MAX_VALUE
        for ((label, feat) in features) {
            val d = chiSquareDistance(query, feat)
            if (d < bestDist) { bestDist = d; bestLabel = label }
        }
        val threshold = method.threshold
        val isUnknown = bestDist > threshold
        val confidence = maxOf(0.0, 1.0 - bestDist / threshold)
        return RecognitionResult(
            if (isUnknown) "Unknown" else bestLabel,
            confidence, bestDist, isUnknown)
    }

    private fun chiSquareDistance(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            if (a[i] + b[i] > 0) {
                val d = a[i] - b[i]
                sum += d * d / (a[i] + b[i])
            }
        }
        return sum
    }

    companion object {
        val Method.threshold: Double
            get() = when (this) {
                Method.LBP -> 100.0
                Method.PCA_EIGENFACE -> 5000.0
            }
    }
}"""
    story.append(Paragraph(normalize_text(code_5), styles['code']))

    # 4.3.6
    story.append(Paragraph(normalize_text("4.3.6 FaceDatabase（人脸特征数据库）"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "职责：管理人脸特征数据库的持久化存储，支持注册新用户、删除用户、查询所有用户。"
        "使用SharedPreferences存储元数据，内部存储目录存储特征向量。"
    ), styles['body']))
    code_6 = """// FaceDatabase.kt
package com.wangyao.imagerecognition.face

import android.content.Context
import java.io.*

class FaceDatabase(private val context: Context) {
    private val prefs = context.getSharedPreferences(
        "face_db", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "face_features").apply {
        if (!exists()) mkdirs()
    }

    data class FaceEntry(
        val id: String, val label: String,
        val featurePath: String, val createdAt: Long
    )

    fun enroll(label: String, feature: FloatArray): String {
        val id = "${label}_${System.currentTimeMillis()}"
        val file = File(dir, "$id.dat")
        ObjectOutputStream(FileOutputStream(file)).use { oos ->
            oos.writeObject(feature)
        }
        prefs.edit()
            .putString("entry_$id", label)
            .putString("path_$id", file.absolutePath)
            .putLong("time_$id", System.currentTimeMillis())
            .apply()
        return id
    }

    fun listAll(): List<FaceEntry> {
        return prefs.all.keys
            .filter { it.startsWith("entry_") }
            .map { key ->
                val id = key.removePrefix("entry_")
                FaceEntry(id,
                    prefs.getString(key, "") ?: "",
                    prefs.getString("path_$id", "") ?: "",
                    prefs.getLong("time_$id", 0))
            }
    }

    fun loadFeature(id: String): FloatArray? {
        val path = prefs.getString("path_$id", null) ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return ObjectInputStream(FileInputStream(file)).use { ois ->
            ois.readObject() as FloatArray
        }
    }

    fun delete(id: String) {
        prefs.getString("path_$id", null)?.let { File(it).delete() }
        prefs.edit()
            .remove("entry_$id").remove("path_$id")
            .remove("time_$id").apply()
    }

    fun count(): Int = prefs.all.keys
        .count { it.startsWith("entry_") }
}"""
    story.append(Paragraph(normalize_text(code_6), styles['code']))

    # 4.3.7
    story.append(Paragraph(normalize_text("4.3.7 VideoStructurer（视频结构化分析器）"), styles['h3']))
    story.append(Paragraph(normalize_text(
        "职责：对视频进行镜头边界检测、关键帧提取、镜头聚类。复用contentsearch模块的ImageFeatures。"
    ), styles['body']))
    code_7 = """// VideoStructurer.kt
package com.wangyao.imagerecognition.core

import com.wangyao.contentsearch.core.ImageFeatures

data class Shot(
    val startFrame: Int, val endFrame: Int,
    val keyFrameIndex: Int, val type: ShotType
)
enum class ShotType { CUT, GRADUAL, SCENE }

data class VideoSummary(
    val totalFrames: Int, val fps: Double, val duration: Double,
    val shots: List<Shot>, val keyFrames: List<Int>,
    val scenes: List<List<Int>>
)

class VideoStructurer(
    private val cutThreshold: Double = 0.5,
    private val gradualThreshold: Double = 0.3,
    private val keyFrameThreshold: Double = 0.15
) {
    fun analyze(videoPath: String, onProgress: (Int, Int) -> Unit
    ): VideoSummary {
        val shots = mutableListOf<Shot>()
        val keyFrames = mutableListOf<Int>()
        var prevHist: FloatArray? = null
        var prevKfHist: FloatArray? = null
        var shotStart = 0
        var gradualStart = -1

        VideoRecognitionPipeline.decodeOnly(videoPath,
            onFrame = { luma, w, h, idx ->
                val hist = ImageFeatures.colorHistogramFromLuma(luma)
                if (prevHist != null) {
                    val dist = histogramDistance(prevHist!!, hist)
                    when {
                        dist > cutThreshold -> {
                            shots.add(Shot(shotStart, idx-1,
                                (shotStart+idx)/2, ShotType.CUT))
                            keyFrames.add(idx)
                            shotStart = idx
                            gradualStart = -1
                        }
                        dist > gradualThreshold -> {
                            if (gradualStart < 0) gradualStart = idx
                        }
                        else -> {
                            if (gradualStart >= 0) {
                                shots.add(Shot(gradualStart, idx,
                                    (gradualStart+idx)/2, ShotType.GRADUAL))
                                keyFrames.add(idx)
                                shotStart = idx
                                gradualStart = -1
                            }
                            if (keyFrames.isNotEmpty() && prevKfHist != null) {
                                val kfDist = histogramDistance(
                                    prevKfHist!!, hist)
                                if (kfDist > keyFrameThreshold) {
                                    keyFrames.add(idx)
                                    prevKfHist = hist
                                }
                            } else {
                                keyFrames.add(idx)
                                prevKfHist = hist
                            }
                        }
                    }
                } else {
                    keyFrames.add(idx)
                    prevKfHist = hist
                }
                prevHist = hist
                onProgress(idx, -1)
            }
        )
        val scenes = clusterShots(shots, keyFrames)
        return VideoSummary(
            totalFrames = shots.lastOrNull()?.endFrame ?: 0,
            fps = 30.0,
            duration = (shots.lastOrNull()?.endFrame ?: 0) / 30.0,
            shots = shots, keyFrames = keyFrames, scenes = scenes)
    }

    private fun histogramDistance(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            if (a[i] + b[i] > 0) {
                val d = a[i] - b[i]
                sum += d * d / (a[i] + b[i])
            }
        }
        return sum
    }

    private fun clusterShots(shots: List<Shot>,
        keyFrames: List<Int>): List<List<Int>> {
        if (shots.isEmpty()) return emptyList()
        return listOf(shots.indices.toList())
    }
}"""
    story.append(Paragraph(normalize_text(code_7), styles['code']))

    # 4.4
    story.append(Paragraph(normalize_text("4.4 JNI/OpenCV集成架构"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "本模块需要在imageCVdeal模块的CMakeLists.txt和native-lib.cpp中新增OpenCV识别相关函数。"
        "imageCVdeal已配置OpenCV 4.x动态库（libopencv_java4.so），"
        "头文件包含opencv2/core、imgproc、objdetect、video、ml、features2d等模块。"
    ), styles['body']))
    story.append(Paragraph(normalize_text("<b>需要新增的OpenCV头文件依赖：</b>"), styles['body']))
    story.append(Paragraph(normalize_text(
        "#include &lt;opencv2/objdetect.hpp&gt;    // CascadeClassifier, HOGDescriptor<br/>"
        "#include &lt;opencv2/video.hpp&gt;          // BackgroundSubtractor, calcOpticalFlow<br/>"
        "#include &lt;opencv2/video/tracking.hpp&gt; // KalmanFilter, goodFeaturesToTrack<br/>"
        "#include &lt;opencv2/features2d.hpp&gt;     // SIFT, ORB, FeatureMatcher<br/>"
        "#include &lt;opencv2/ml.hpp&gt;             // SVM, KNearest"
    ), styles['code']))
    story.append(Paragraph(normalize_text("<b>OpencvDealJni.kt 新增接口：</b>"), styles['body']))
    jni_code = """// OpencvDealJni.kt 新增方法
object OpencvDealJni {
    init {
        System.loadLibrary("opencv_java4")
        System.loadLibrary("opencvdeal_native")
    }
    // -- 运动检测 --
    external fun nMotionDetectMOG2(gray: ByteArray, w: Int, h: Int,
        history: Int, varThreshold: Double): ByteArray
    external fun nMotionDetectKNN(gray: ByteArray, w: Int, h: Int,
        history: Int, distThreshold: Double): ByteArray
    // -- 目标跟踪 --
    external fun nOpticalFlowLK(prevGray: ByteArray, curGray: ByteArray,
        w: Int, h: Int, prevPoints: FloatArray): FloatArray
    external fun nOpticalFlowFarneback(prevGray: ByteArray, curGray: ByteArray,
        w: Int, h: Int): FloatArray
    // -- Haar级联检测 --
    external fun nHaarDetect(gray: ByteArray, w: Int, h: Int,
        cascadePath: String, scaleFactor: Double, minNeighbors: Int,
        minWidth: Int, minHeight: Int): FloatArray
    // -- HOG行人检测 --
    external fun nHogDetect(gray: ByteArray, w: Int, h: Int,
        hitThreshold: Double, winStride: Int,
        padding: Int, scale: Double): FloatArray
    // -- 特征点检测与匹配 --
    external fun nSiftDetectAndCompute(gray: ByteArray, w: Int, h: Int
    ): SiftResult
    external fun nFeatureMatch(desc1: FloatArray, desc2: FloatArray,
        method: Int): IntArray
    // -- 人脸相关 --
    external fun nExtractLBP(gray: ByteArray, w: Int, h: Int,
        gridX: Int, gridY: Int): FloatArray
    external fun nComputePCA(faces: Array<ByteArray>, w: Int, h: Int,
        numComponents: Int): PCAResult
    // -- Meanshift/Camshift --
    external fun nMeanshift(backProj: ByteArray, w: Int, h: Int,
        window: IntArray): IntArray
    external fun nCamshift(backProj: ByteArray, w: Int, h: Int,
        window: IntArray): CamshiftResult
}"""
    story.append(Paragraph(normalize_text(jni_code), styles['code']))

    # 4.5
    story.append(Paragraph(normalize_text("4.5 视频处理管线设计"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "视频处理管线沿用项目已有的MediaExtractor + MediaCodec解码架构，"
        "提取YUV_420_888的Y亮度平面后送入识别算法。"
        "对于需要彩色信息的算法（如HSV颜色直方图），同时提取UV平面。"
    ), styles['body']))
    pipeline_code = """
digraph {
    rankdir=LR
    nodesep=0.25
    ranksep=0.4
    node [shape=box style="rounded,filled" fontname="Noto Sans CJK SC" fontsize=11 margin="0.15,0.10"]
    extract [label="MediaExtractor\\n(提取视频轨道)" fillcolor="#E8F4FD"]
    decode [label="MediaCodec\\n(解码H.264)" fillcolor="#BBDEFB"]
    image [label="Image(YUV_420_888)\\nY+UV平面" fillcolor="#C8E6C9"]
    yplane [label="Y亮度平面\\nByteArray" fillcolor="#FFF9C4"]
    algo [label="识别算法层\\n(MotionDetector/\\nObjectTracker/\\nHaarDetector/\\nHogDetector/\\nFaceDetector)" fillcolor="#FFE0B2"]
    result [label="检测结果\\nFrameResult" fillcolor="#E1BEE7"]
    render [label="渲染叠加\\n检测框/轨迹" fillcolor="#FFCDD2"]
    extract -> decode -> image -> yplane -> algo -> result -> render
}
"""
    render_graphviz_to_png(pipeline_code, "/tmp/video_pipeline.png")
    add_figure(story, "/tmp/video_pipeline.png", "图 4-2: 视频处理管线流程", styles)

    # 4.6
    story.append(Paragraph(normalize_text("4.6 UI架构设计"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "UI层在app模块中新增Fragment和路由配置。沿用项目已有的FFViewModel + MainActivity路由架构。"
    ), styles['body']))
    ui_code = """// 新增Fragment列表
// 1. 菜单Fragment (二级菜单)
RecognitionMenuFragment.kt - 视频识别/物体识别/人脸识别入口

// 2. 视频识别Fragment
VideoMotionDetectFragment.kt    // 运动目标检测
VideoTrackingFragment.kt        // 目标跟踪
VideoStructureFragment.kt       // 视频结构化分析

// 3. 物体识别Fragment
ObjectDetectFragment.kt         // Haar/HOG目标检测
ShapeRecognitionFragment.kt     // 形状识别(增强版)
FeatureMatchFragment.kt          // SIFT/ORB特征匹配

// 4. 人脸识别Fragment
FaceDetectFragment.kt            // 人脸检测
FaceRecognizeFragment.kt         // 人脸注册与识别
FaceVideoFragment.kt             // 视频人脸检测与跟踪

// 路由配置 (FFViewModel.kt)
fun navigateToRecognition(target: RecognitionTarget) {
    val fragment = when (target) {
        RecognitionTarget.MENU -> RecognitionMenuFragment()
        RecognitionTarget.VIDEO_MOTION -> VideoMotionDetectFragment()
        RecognitionTarget.VIDEO_TRACKING -> VideoTrackingFragment()
        RecognitionTarget.VIDEO_STRUCTURE -> VideoStructureFragment()
        RecognitionTarget.OBJECT_DETECT -> ObjectDetectFragment()
        RecognitionTarget.FACE_DETECT -> FaceDetectFragment()
        RecognitionTarget.FACE_RECOGNIZE -> FaceRecognizeFragment()
        RecognitionTarget.FACE_VIDEO -> FaceVideoFragment()
    }
    switchFragment.postValue(fragment)
}"""
    story.append(Paragraph(normalize_text(ui_code), styles['code']))

    # 4.7
    story.append(Paragraph(normalize_text("4.7 数据流设计"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "模块内部数据流遵循统一的输入->处理->输出模式："
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>图像检测流程：</b>Bitmap/ByteArray -> JNI灰度转换 -> "
        "OpenCV Mat -> HaarDetector/HogDetector/FaceDetector -> "
        "Detection列表 -> 叠加渲染到Canvas -> 显示"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>视频识别流程：</b>MediaExtractor -> MediaCodec -> "
        "YUV Y平面 -> MotionDetector/ObjectTracker -> FrameResult -> "
        "逐帧叠加渲染 -> 输出标注视频"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>人脸识别流程：</b>人脸检测 -> 人脸对齐(仿射变换) -> "
        "FaceRecognizer.extractFeature -> FaceDatabase.loadAll -> "
        "最近邻匹配 -> RecognitionResult"
    ), styles['body']))

    # CH5
    story.append(Paragraph(normalize_text("第五章 核心算法实现细节"), styles['h1']))
    story.append(h1_divider())

    story.append(Paragraph(normalize_text("5.1 运动检测算法实现细节"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "运动检测支持三种方法：帧差法、MOG2混合高斯、KNN背景减除。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>帧差法实现要点：</b>"
        "(1) 相邻帧灰度差分 D = |I(t) - I(t-1)|；"
        "(2) 阈值二值化 B = D > T ? 255 : 0，T默认25；"
        "(3) 形态学开运算去噪、闭运算填洞；"
        "(4) findContours提取连通域外接矩形；"
        "(5) 面积过滤去除小噪声区域。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>MOG2实现要点：</b>"
        "(1) 初始化BackgroundSubtractorMOG2(history=500, varThreshold=16, detectShadows=true)；"
        "(2) 逐帧apply更新背景模型并输出前景掩码；"
        "(3) 阴影像素值为127，可选保留或去除；"
        "(4) 形态学后处理同帧差法。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("5.2 目标跟踪算法实现细节"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "<b>NCC模板跟踪（已有基础增强）：</b>"
        "(1) 初始化：首帧在指定位置提取48x48灰度模板；"
        "(2) 搜索：在上一帧位置周围searchRadius=32像素范围内用TM_CCOEFF_NORMED匹配；"
        "(3) 更新：取NCC最大值位置作为新目标位置，若score > lostThreshold=0.5则跟踪成功；"
        "(4) 丢失处理：连续lostCount超过阈值则进入重定位模式；"
        "(5) 增强多尺度：在3个缩放因子(0.8, 1.0, 1.2)下搜索，适应目标尺寸变化。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>光流跟踪实现要点：</b>"
        "(1) goodFeaturesToTrack在ROI内检测最多50个Shi-Tomasi角点；"
        "(2) calcOpticalFlowPyrLK计算前后帧特征点对应关系，金字塔层数默认3；"
        "(3) 统计成功跟踪的特征点中心作为目标位置；"
        "(4) 剔除外点（距离中位数超过2倍MAD的点）；"
        "(5) 定期补充新特征点防止跟踪退化。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("5.3 Haar级联检测算法实现细节"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "<b>核心流程：</b>"
        "(1) 加载XML级联模型文件（从assets复制到内部存储）；"
        "(2) 多尺度滑动窗口扫描：从minSize到maxSize，每次乘scaleFactor(1.1-1.3)；"
        "(3) 每个窗口通过级联分类器，前几级快速排除大部分负样本；"
        "(4) NMS去重：对重叠率超过0.3的检测框保留置信度最高的；"
        "(5) 嵌套检测：在人脸区域内检测眼睛/鼻子等子部件。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>NMS（非极大值抑制）实现：</b>"
        "(1) 按置信度降序排序所有检测框；"
        "(2) 取最高分框加入结果列表，移除与其IoU > 阈值(0.3)的所有框；"
        "(3) 重复直到候选框为空。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("5.4 HOG行人检测算法实现细节"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "(1) 使用HOGDescriptor默认参数：winSize=(64,128), blockSize=(16,16), "
        "cellSize=(8,8), bin=9, 总特征维度=3780；"
        "(2) 加载getDefaultPeopleDetector()预训练SVM权重；"
        "(3) detectMultiScale多尺度检测，winStride=(8,8)控制滑动步长，scale=1.05；"
        "(4) NMS去重同Haar检测；"
        "(5) 输出检测框和置信度权重。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("5.5 人脸识别算法实现细节"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "<b>LBP特征提取流程：</b>"
        "(1) 人脸对齐：利用眼睛位置计算旋转角度，仿射变换归一化为100x100正面人脸；"
        "(2) 逐像素计算3x3邻域LBP编码（8位二进制，256种模式）；"
        "(3) 映射为Uniform LBP（最多2次0-1跳变的模式，共58种+1种其他=59类）；"
        "(4) 7x7=49个子区域分别统计59维直方图；"
        "(5) L2归一化每个子区域直方图；"
        "(6) 串联为49*59=2891维特征向量。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>人脸匹配流程：</b>"
        "(1) 计算查询特征与数据库所有特征之间的卡方距离；"
        "(2) 取距离最小者作为候选；"
        "(3) 若距离 < 阈值(100.0)则判定为已知身份，否则标记为未知；"
        "(4) 置信度 = 1 - distance/threshold。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("5.6 视频结构化分析实现细节"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "<b>镜头边界检测：</b>"
        "(1) 逐帧提取HSV颜色直方图（复用contentsearch.ImageFeatures）；"
        "(2) 计算相邻帧直方图卡方距离；"
        "(3) 双阈值判定：距离 > cutThreshold(0.5)为突变，"
        "gradualThreshold(0.3) < 距离 < cutThreshold为渐变；"
        "(4) 同时计算帧间距离D(k,k+1)和跨帧距离D(k,k+L)提高检测精度。"
    ), styles['body']))
    story.append(Paragraph(normalize_text(
        "<b>关键帧提取：</b>"
        "(1) 基于颜色特征法：比较当前帧与上一个关键帧的直方图距离；"
        "(2) 距离 > keyFrameThreshold(0.15)则选为新关键帧；"
        "(3) 每个镜头至少包含首帧和末帧作为关键帧。"
    ), styles['body']))

    # CH6
    story.append(Paragraph(normalize_text("第六章 接口定义"), styles['h1']))
    story.append(h1_divider())
    story.append(Paragraph(normalize_text("6.1 JNI接口规范"), styles['h2']))
    jni_interface_data = [
        ["接口名", "参数", "返回值", "功能"],
        ["nMotionDetectMOG2", "gray,w,h,history,varThreshold", "ByteArray(前景掩码)", "MOG2背景减除"],
        ["nMotionDetectKNN", "gray,w,h,history,distThreshold", "ByteArray(前景掩码)", "KNN背景减除"],
        ["nOpticalFlowLK", "prevGray,curGray,w,h,prevPoints", "FloatArray(点+状态)", "LK金字塔光流"],
        ["nOpticalFlowFarneback", "prevGray,curGray,w,h", "FloatArray(光流场)", "Farneback稠密光流"],
        ["nHaarDetect", "gray,w,h,cascadePath,scale,neighbors,minW,minH", "FloatArray(检测框列表)", "Haar级联检测"],
        ["nHogDetect", "gray,w,h,hitThreshold,winStride,padding,scale", "FloatArray(检测框列表)", "HOG行人检测"],
        ["nSiftDetectAndCompute", "gray,w,h", "SiftResult(关键点+描述子)", "SIFT特征提取"],
        ["nFeatureMatch", "desc1,desc2,method", "IntArray(匹配对索引)", "特征点匹配"],
        ["nExtractLBP", "gray,w,h,gridX,gridY", "FloatArray(LBP直方图)", "LBP特征提取"],
        ["nComputePCA", "faces[],w,h,numComponents", "PCAResult(均值+特征向量)", "PCA特征脸计算"],
        ["nMeanshift", "backProj,w,h,window[]", "IntArray(新窗口)", "Meanshift跟踪"],
        ["nCamshift", "backProj,w,h,window[]", "CamshiftResult(窗口+角度)", "Camshift跟踪"],
    ]
    story.append(make_table(jni_interface_data, col_widths=[1.4*inch, 2.0*inch, 1.5*inch, 1.3*inch]))

    story.append(Paragraph(normalize_text("6.2 Kotlin API接口规范"), styles['h2']))
    api_data = [
        ["类名", "方法签名", "功能"],
        ["MotionDetector", "detect(gray: Mat): Detection", "检测运动目标"],
        ["ObjectTracker", "init(gray: Mat, x: Int, y: Int)", "初始化跟踪器"],
        ["ObjectTracker", "track(gray: Mat): TrackResult", "执行单帧跟踪"],
        ["HaarDetector", "detect(gray: Mat): List<Detection>", "Haar级联目标检测"],
        ["HaarDetector", "detectNested(gray, nested): Map", "多级联嵌套检测"],
        ["HogDetector", "detect(gray: Mat): List<Detection>", "HOG行人检测"],
        ["FaceDetector", "detect(gray: Mat): List<FaceDetection>", "人脸+眼睛检测"],
        ["FaceRecognizer", "enroll(label: String, face: Mat)", "注册人脸"],
        ["FaceRecognizer", "recognize(face: Mat): RecognitionResult", "识别人脸"],
        ["FaceDatabase", "enroll(label, feature): String", "存入人脸特征"],
        ["FaceDatabase", "loadFeature(id: String): FloatArray?", "加载人脸特征"],
        ["VideoStructurer", "analyze(videoPath, onProgress): VideoSummary", "视频结构化分析"],
    ]
    story.append(make_table(api_data, col_widths=[1.3*inch, 2.8*inch, 2.1*inch]))

    story.append(Paragraph(normalize_text("6.3 Fragment路由接口"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "通过FFViewModel.switchFragment LiveData路由Fragment，"
        "菜单层级为：主菜单(已有) -> 图像识别菜单(新增) -> 各功能页面。"
    ), styles['body']))

    # CH7
    story.append(Paragraph(normalize_text("第七章 资源与国际化"), styles['h1']))
    story.append(h1_divider())
    story.append(Paragraph(normalize_text("7.1 资源文件"), styles['h2']))
    res_data = [
        ["文件路径", "类型", "说明"],
        ["layout/fragment_recognition_menu.xml", "布局", "识别功能二级菜单"],
        ["layout/fragment_video_motion.xml", "布局", "运动检测页面"],
        ["layout/fragment_video_tracking.xml", "布局", "目标跟踪页面"],
        ["layout/fragment_video_structure.xml", "布局", "视频结构化分析页面"],
        ["layout/fragment_object_detect.xml", "布局", "物体检测页面"],
        ["layout/fragment_face_detect.xml", "布局", "人脸检测页面"],
        ["layout/fragment_face_recognize.xml", "布局", "人脸注册与识别页面"],
        ["layout/fragment_face_video.xml", "布局", "视频人脸检测页面"],
        ["values/strings_recognition.xml", "字符串", "中文字符串资源"],
        ["values-en/strings_recognition.xml", "字符串", "英文字符串资源"],
        ["raw/haarcascade_frontalface.xml", "原始", "Haar人脸级联模型"],
        ["raw/haarcascade_eye.xml", "原始", "Haar眼睛级联模型"],
    ]
    story.append(make_table(res_data, col_widths=[2.6*inch, 0.7*inch, 2.9*inch]))

    story.append(Paragraph(normalize_text("7.2 字符串资源（部分）"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "新增约120条中英文字符串，覆盖菜单标题、按钮文本、算法参数标签、状态提示等。"
    ), styles['body']))
    strings_code = """<!-- values/strings_recognition.xml -->
<resources>
    <!-- 菜单 -->
    <string name="recognition_menu_title">视频识别及物体/人脸识别</string>
    <string name="recognition_video_section">视频识别</string>
    <string name="recognition_object_section">物体识别</string>
    <string name="recognition_face_section">人脸识别</string>
    <!-- 视频识别 -->
    <string name="motion_detect_title">运动目标检测</string>
    <string name="tracking_title">目标跟踪</string>
    <string name="video_structure_title">视频结构化分析</string>
    <string name="method_frame_diff">帧差法</string>
    <string name="method_mog2">混合高斯(MOG2)</string>
    <string name="method_knn">KNN背景减除</string>
    <string name="track_ncc">NCC模板跟踪</string>
    <string name="track_optical_flow">光流跟踪</string>
    <!-- 物体识别 -->
    <string name="haar_detect_title">Haar目标检测</string>
    <string name="hog_detect_title">HOG行人检测</string>
    <string name="feature_match_title">特征点匹配</string>
    <!-- 人脸识别 -->
    <string name="face_detect_title">人脸检测</string>
    <string name="face_recognize_title">人脸识别</string>
    <string name="face_enroll">注册人脸</string>
    <string name="method_lbp">LBP特征</string>
    <string name="method_pca">PCA特征脸</string>
    <!-- 参数 -->
    <string name="param_scale_factor">缩放因子</string>
    <string name="param_min_neighbors">最小邻域数</string>
    <string name="param_lost_threshold">丢失阈值</string>
    <string name="param_search_radius">搜索半径</string>
    <string name="param_template_size">模板尺寸</string>
    <!-- 状态 -->
    <string name="status_detecting">检测中...</string>
    <string name="status_tracking">跟踪中...</string>
    <string name="status_target_lost">目标丢失</string>
    <string name="status_no_face">未检测到人脸</string>
    <string name="status_recognized">识别为: %1$s (置信度: %2$.1f%%)</string>
    <string name="status_unknown_face">未知人脸</string>
</resources>"""
    story.append(Paragraph(normalize_text(strings_code), styles['code']))

    story.append(Paragraph(normalize_text("7.3 菜单系统扩展"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "在现有主菜单中新增\"视频识别及物体/人脸识别\"入口卡片，"
        "与\"图像增强\"、\"数字水印技术\"、\"图片与视频质量评价\"等平级。"
        "点击进入二级菜单(RecognitionMenuFragment)，展示三个子系统入口。"
    ), styles['body']))

    # CH8
    story.append(Paragraph(normalize_text("第八章 OpenCV函数映射"), styles['h1']))
    story.append(h1_divider())
    story.append(Paragraph(normalize_text(
        "教材以MATLAB为编程工具，本节将教材涉及的识别相关原理映射到OpenCV函数实现："
    ), styles['body']))
    opencv_map = [
        ["教材内容", "OpenCV函数/类", "用途"],
        ["Canny边缘检测(Ch.4)", "Imgproc.Canny()", "边缘检测，目标轮廓提取"],
        ["形态学操作(Ch.3)", "Imgproc.erode/dilate/morphologyEx()", "前景掩码后处理"],
        ["阈值化(Ch.4)", "Imgproc.threshold/adaptiveThreshold()", "二值化"],
        ["模板匹配(NCC)", "Imgproc.matchTemplate(TM_CCOEFF_NORMED)", "目标定位"],
        ["块匹配运动估计(Ch.5)", "calcOpticalFlowFarneback", "运动矢量计算"],
        ["Haar人脸检测(Ch.11)", "CascadeClassifier.detectMultiScale()", "人脸/目标检测"],
        ["HOG行人检测", "HOGDescriptor.detectMultiScale()", "行人检测"],
        ["SIFT特征点(Ch.11)", "SIFT.create()/detectAndCompute()", "特征点提取"],
        ["光流法(Ch.9)", "Video.calcOpticalFlowPyrLK()", "稀疏光流跟踪"],
        ["稠密光流(Ch.9)", "Video.calcOpticalFlowFarneback()", "稠密光流场"],
        ["MOG2背景建模", "Video.createBackgroundSubtractorMOG2()", "混合高斯背景减除"],
        ["KNN背景建模", "Video.createBackgroundSubtractorKNN()", "KNN背景减除"],
        ["Meanshift跟踪", "Video.meanShift()", "概率密度梯度跟踪"],
        ["Camshift跟踪", "Video.CamShift()", "自适应窗口跟踪"],
        ["Shi-Tomasi角点", "Imgproc.goodFeaturesToTrack()", "光流跟踪特征点"],
        ["连通域分析", "Imgproc.findContours()", "前景目标提取"],
        ["仿射变换", "Imgproc.warpAffine()", "人脸对齐"],
        ["SVM分类器(Ch.11)", "ml.SVM.create()/train/predict()", "物体分类"],
        ["HSV颜色空间", "Imgproc.cvtColor(COLOR_BGR2HSV)", "颜色特征提取"],
    ]
    story.append(make_table(opencv_map, col_widths=[1.7*inch, 2.4*inch, 2.1*inch]))

    # CH9
    story.append(Paragraph(normalize_text("第九章 Haar级联模型文件"), styles['h1']))
    story.append(h1_divider())
    story.append(Paragraph(normalize_text(
        "Haar级联分类器需要预训练的XML模型文件。OpenCV提供了多种预训练模型，"
        "可从OpenCV官方仓库获取并打包到项目的assets或raw资源目录中。"
    ), styles['body']))
    model_data = [
        ["模型文件名", "检测目标", "文件大小", "来源"],
        ["haarcascade_frontalface_default.xml", "正脸人脸", "~1MB", "OpenCV官方"],
        ["haarcascade_frontalface_alt2.xml", "正脸人脸(备选)", "~0.5MB", "OpenCV官方"],
        ["haarcascade_profileface.xml", "侧脸人脸", "~0.5MB", "OpenCV官方"],
        ["haarcascade_eye.xml", "眼睛", "~0.5MB", "OpenCV官方"],
        ["haarcascade_eye_tree_eyeglasses.xml", "眼睛(戴眼镜)", "~1MB", "OpenCV官方"],
        ["haarcascade_mcs_nose.xml", "鼻子", "~0.3MB", "OpenCV contrib"],
        ["haarcascade_mcs_mouth.xml", "嘴巴", "~0.3MB", "OpenCV contrib"],
        ["haarcascade_fullbody.xml", "人体全身", "~0.5MB", "OpenCV官方"],
        ["haarcascade_upperbody.xml", "人体上半身", "~0.5MB", "OpenCV官方"],
        ["haarcascade_lowerbody.xml", "人体下半身", "~0.5MB", "OpenCV官方"],
        ["haarcascade_car.xml", "车辆(正面)", "~0.5MB", "OpenCV官方"],
    ]
    story.append(make_table(model_data, col_widths=[2.2*inch, 1.1*inch, 0.7*inch, 1.5*inch]))
    story.append(Paragraph(normalize_text(
        "模型文件加载流程：(1) 从assets复制到内部存储目录；"
        "(2) CascadeClassifier.load(path)加载；"
        "(3) 首次加载缓存路径，后续直接使用。"
    ), styles['body']))

    # CH10
    story.append(Paragraph(normalize_text("第十章 测试方案"), styles['h1']))
    story.append(h1_divider())
    story.append(Paragraph(normalize_text("10.1 单元测试"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "在imagerecognition模块的src/test/java目录下新增单元测试，覆盖核心算法逻辑的正确性。"
    ), styles['body']))
    test_data = [
        ["测试类", "测试方法", "测试内容", "通过条件"],
        ["MotionDetectorTest", "testFrameDiff", "帧差法检测静态帧", "motionRatio=0, boxes为空"],
        ["MotionDetectorTest", "testFrameDiffMotion", "帧差法检测运动帧", "motionRatio>0, boxes非空"],
        ["MotionDetectorTest", "testMOG2", "MOG2背景建模", "前景掩码正确提取"],
        ["ObjectTrackerTest", "testNCCInit", "NCC模板初始化", "模板尺寸/位置正确"],
        ["ObjectTrackerTest", "testNCCTrack", "NCC单帧跟踪", "score>阈值,位置更新"],
        ["ObjectTrackerTest", "testNCCLost", "NCC目标丢失", "score<阈值,isLost=true"],
        ["HaarDetectorTest", "testLoadCascade", "加载级联模型", "cascade.load()=true"],
        ["HaarDetectorTest", "testFaceDetect", "人脸检测", "检测到>=1个人脸框"],
        ["FaceRecognizerTest", "testLBPExtract", "LBP特征提取", "维度正确(2891维)"],
        ["FaceRecognizerTest", "testEnrollRecognize", "注册并识别", "label正确,confidence>0.5"],
        ["FaceDatabaseTest", "testEnrollLoad", "注册并加载", "特征向量一致"],
        ["FaceDatabaseTest", "testDelete", "删除人脸", "count减1"],
        ["VideoStructurerTest", "testShotDetect", "镜头边界检测", "突变镜头正确识别"],
        ["VideoStructurerTest", "testKeyFrameExtract", "关键帧提取", "关键帧数量合理"],
    ]
    story.append(make_table(test_data, col_widths=[1.3*inch, 1.1*inch, 2.2*inch, 1.7*inch]))

    story.append(Paragraph(normalize_text("10.2 集成测试"), styles['h2']))
    story.append(Paragraph(normalize_text(
        "集成测试在设备上运行，使用项目的测试素材："
        "(1) 静态图像：app/src/main/assets/IMG_20260821_190758.jpg - "
        "测试人脸检测、Haar目标检测、HOG行人检测、形状识别等；"
        "(2) 测试视频：app/src/main/assets/video.mp4 - "
        "测试运动目标检测、目标跟踪、视频结构化分析、视频人脸检测等。"
    ), styles['body']))

    story.append(Paragraph(normalize_text("10.3 验收标准"), styles['h2']))
    accept_data = [
        ["功能", "验收标准", "测试素材"],
        ["运动目标检测", "正确检测视频中运动区域，输出前景掩码和目标框", "video.mp4"],
        ["目标跟踪", "持续跟踪指定目标，轨迹连续无明显跳变", "video.mp4"],
        ["Haar人脸检测", "静态图像人脸检测召回率>=90%，误检率<10%", "IMG_20260821_190758.jpg"],
        ["HOG行人检测", "能检测图像中的行人，检测框位置基本准确", "IMG_20260821_190758.jpg"],
        ["人脸识别", "注册后能正确识别同一人脸，未注册标记Unknown", "手动测试"],
        ["视频结构化", "正确识别镜头边界，提取合理数量关键帧", "video.mp4"],
        ["视频人脸检测", "逐帧检测人脸并跟踪，多脸场景不混淆", "video.mp4"],
        ["性能", "图像检测<500ms，视频处理>=10fps", "所有素材"],
        ["UI/UX", "页面布局正确，中英文字符串无缺失", "手动测试"],
    ]
    story.append(make_table(accept_data, col_widths=[1.3*inch, 3.0*inch, 2.0*inch]))

    # CH11
    story.append(Paragraph(normalize_text("第十一章 开发计划"), styles['h1']))
    story.append(h1_divider())
    plan_data = [
        ["阶段", "任务", "工期", "依赖"],
        ["阶段1", "CMakeLists新增OpenCV objdetect/video/ml模块链接", "0.5天", "无"],
        ["阶段1", "native-lib.cpp新增JNI接口框架(12个方法签名)", "0.5天", "无"],
        ["阶段2", "MotionDetector.kt实现(帧差/MOG2/KNN)", "1天", "阶段1"],
        ["阶段2", "ObjectTracker.kt实现(NCC增强+光流)", "1.5天", "阶段1"],
        ["阶段2", "VideoStructurer.kt实现(镜头检测/关键帧)", "1天", "阶段1"],
        ["阶段3", "HaarDetector.kt实现+模型文件集成", "1天", "阶段1"],
        ["阶段3", "HogDetector.kt实现", "0.5天", "阶段1"],
        ["阶段3", "FeatureMatcher.kt实现(SIFT/ORB)", "1天", "阶段1"],
        ["阶段4", "FaceDetector.kt实现(人脸+眼睛检测)", "1天", "阶段3"],
        ["阶段4", "FaceRecognizer.kt实现(LBP+PCA)", "2天", "阶段3"],
        ["阶段4", "FaceDatabase.kt实现(持久化存储)", "0.5天", "阶段4"],
        ["阶段5", "JNI C++层实现(各native方法)", "3天", "阶段2-4"],
        ["阶段6", "8个Fragment页面实现+路由", "2天", "阶段2-4"],
        ["阶段6", "字符串国际化(中英文120条)", "0.5天", "阶段6"],
        ["阶段7", "单元测试编写(14个测试类)", "1.5天", "阶段5"],
        ["阶段7", "集成测试+验收测试", "1天", "阶段6"],
        ["阶段8", "Bug修复+性能优化", "1天", "阶段7"],
        ["", "合计", "约19天", ""],
    ]
    story.append(make_table(plan_data, col_widths=[0.5*inch, 3.0*inch, 0.7*inch, 1.1*inch]))

    # CH12
    story.append(Paragraph(normalize_text("第十二章 风险与对策"), styles['h1']))
    story.append(h1_divider())
    risk_data = [
        ["风险", "影响", "概率", "对策"],
        ["OpenCV objdetect模块未编译进so", "Haar/HOG检测无法使用", "低", "确认OpenCV编译包含objdetect模块"],
        ["Haar模型文件过大", "APK体积膨胀", "中", "使用ProGuard压缩或网络下载"],
        ["视频逐帧检测性能不足", "帧率<10fps", "中", "降分辨率至320x240或跳帧+跟踪"],
        ["光流法计算量大", "帧率下降", "中", "使用稀疏光流(LK)替代稠密(Farneback)"],
        ["人脸识别精度不足", "误识/拒识率高", "中", "增加训练样本，调整参数和阈值"],
        ["OpenCV版本兼容性", "API变更编译错误", "低", "锁定OpenCV版本，查阅4.x文档"],
        ["JNI内存泄漏", "长时间运行OOM", "中", "确保Mat对象及时release()"],
        ["多目标跟踪ID切换", "跟踪不稳定", "中", "使用匈牙利匹配优化数据关联"],
    ]
    story.append(make_table(risk_data, col_widths=[1.6*inch, 1.4*inch, 0.5*inch, 2.8*inch]))

    # Appendix
    story.append(Paragraph(normalize_text("附录"), styles['h1']))
    story.append(h1_divider())
    story.append(Paragraph(normalize_text("附录A：教材章节与功能映射表"), styles['h2']))
    appendix_a = [
        ["教材章节", "对应功能模块", "核心算法"],
        ["第4章 图像分割", "MotionDetector(前景提取)", "Canny边缘、区域生长、形态学"],
        ["第5章 压缩编码原理", "ObjectTracker(运动估计)", "块匹配BMA、运动补偿"],
        ["第6章 压缩编码标准", "ObjectTracker(多精度搜索)", "H.264变块运动补偿、1/4像素精度"],
        ["第9章 质量评价", "MotionDetector(光流法)", "光流法运动信息提取"],
        ["第10章 内容检索", "VideoStructurer", "镜头检测、关键帧、场景聚类"],
        ["第11章 图像识别", "HaarDetector/HogDetector/FaceRecognizer", "SVM、CNN理论、Haar特征、统计学习"],
    ]
    story.append(make_table(appendix_a, col_widths=[1.7*inch, 2.0*inch, 2.6*inch]))

    story.append(Paragraph(normalize_text("附录B：已有代码文件清单"), styles['h2']))
    appendix_b = [
        ["文件路径", "所属模块", "功能"],
        ["imageCVdeal/.../native-lib.cpp", "imageCVdeal", "JNI原生实现入口"],
        ["imageCVdeal/.../CMakeLists.txt", "imageCVdeal", "CMake构建配置"],
        ["imageCVdeal/.../OpencvDealJni.kt", "imageCVdeal", "75个JNI接口方法"],
        ["imagerecognition/.../NccMatcher.kt", "imagerecognition", "NCC模板匹配(需增强)"],
        ["imagerecognition/.../ShapeRecognizer.kt", "imagerecognition", "Hu不变矩形状识别(需增强)"],
        ["imagerecognition/.../VideoRecognitionPipeline.kt", "imagerecognition", "视频识别管线(需增强)"],
        ["contentsearch/.../ImageFeatures.kt", "contentsearch", "HSV颜色直方图+纹理特征"],
        ["contentsearch/.../ImageSearchEngine.kt", "contentsearch", "图像检索引擎"],
        ["app/.../MainActivity.kt", "app", "Fragment路由"],
        ["app/.../FFViewModel.kt", "app", "ViewModel路由控制"],
    ]
    story.append(make_table(appendix_b, col_widths=[2.7*inch, 1.1*inch, 2.5*inch]))

    story.append(Paragraph(normalize_text("附录C：技术栈版本信息"), styles['h2']))
    appendix_c = [
        ["技术", "版本", "说明"],
        ["Kotlin", "项目配置版本", "主开发语言"],
        ["Java", "11", "编译兼容版本"],
        ["C++", "C++11", "JNI原生层"],
        ["OpenCV", "4.x", "libopencv_java4.so动态库"],
        ["AGP", "9.2.1", "构建工具"],
        ["compileSdk", "37", "编译SDK版本"],
        ["minSdk", "24", "最低支持版本(Android 7.0)"],
        ["CMake", "3.22.1", "原生构建工具"],
        ["STL", "c++_shared", "C++标准库"],
        ["ABI", "arm64-v8a, armeabi-v7a", "支持架构"],
    ]
    story.append(make_table(appendix_c, col_widths=[1.6*inch, 1.1*inch, 3.6*inch]))

    # Build
    doc = SimpleDocTemplate(
        OUTPUT_PDF,
        pagesize=PAGE_SIZE,
        leftMargin=LEFT_MARGIN,
        rightMargin=RIGHT_MARGIN,
        topMargin=TOP_MARGIN,
        bottomMargin=BOTTOM_MARGIN,
        title="视频识别及物体/人脸识别 代码实现需求文档",
        author="OpencvProcessing Project",
        subject="Requirements Document",
    )
    doc.build(story)
    print(f"PDF generated: {OUTPUT_PDF}")

if __name__ == "__main__":
    build_document()
