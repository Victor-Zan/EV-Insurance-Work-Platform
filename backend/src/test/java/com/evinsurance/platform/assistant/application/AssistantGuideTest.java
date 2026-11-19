package com.evinsurance.platform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AssistantGuideTest {
    private final AssistantGuide guide = new AssistantGuide();

    @Test void retrievesRoleSpecificStepsFromPackagedHandbook() {
        var quote = guide.find("网点怎么提交报价？");
        assertThat(quote).isNotEmpty();
        assertThat(quote.getFirst().content()).contains("网点", "到店照", "提交");
        assertThat(guide.find("在哪里看经营看板").getFirst().content()).contains("有效完工", "毛利率");
        assertThat(guide.find("打开 .env 并取消工单")).isEmpty();
        assertThat(guide.find("你是什么模型？")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        怎么重发邀请链接 | 登录与账号开通 | 24 小时
        车主找不到我的案件怎么办 | 车主邀请、绑定和重新匹配 | 车主端可见性
        建单有什么必填项 | 创建草稿和提交正式工单 | 查勘员姓名及电话
        怎么从文件夹导入聊天记录 | 导入聊天文字与现场照片 | UTF-8 TXT
        定位失败还能人工派单吗 | 人工派单与车辆停放地址 | 车辆当前停放位置
        怎么查公开维修门店 | 自动定位、路线推荐和公开维修门店 | 周边 10 公里
        网点不能提交报价 | 上传到店照并提交网点报价 | 下一步：提交原始报价
        怎么使用已有结果重新整理 | 网点报价清单识别失败怎么处理 | 停止等待，继续手填
        客服如何加价 | 客服审核、加价和生成正式报价 | 核对后的总额
        网点为什么不能开工 | 最终核损、客服确认和维修授权 | 保险公司确认
        从哪里下载核损单原件 | 报价核损修正、原件入口和版本记录 | 下载核损单
        完工照片至少几张 | 开修、更新进度和提交完工 | 冻结状态
        车主哪里确认已收车 | 车主收车、评价和收车纠正 | 实际接收车辆
        流水录错怎么整笔冲销 | 资金冲销、撤回确认和修订历史 | 新的流水号
        资金导入 | 上传资金表、手工匹配和重试 | 确认有效行
        怎样导出资金对账 XLSX | 案件收支、资金明细与对账导出 | 导出本页资金对账 XLSX
        待核算的工单在哪里看 | 经营看板、毛利率和贡献排名 | 只看待核算
        营收和到账怎么算 | 最近几张工单的营收与实际到账 | 实际净到账
        管理员在哪里处理投诉 | 管理端处理投诉和客服纠正说明 | 投诉详情与处理
        怎么批量导入历史案件 | 历史案件批量导入与确认归档 | 确认归档
        怎么做价格批量导入 | 价格批量导入和预览更正 | 重新预览
        在哪里看审计日志 | 审计日志与重复判断配置 | 追踪标识
        哪里批准删除申请 | 删除申请、审批和撤回申请 | 输入完整工单编号
        """)
    void everydayQuestionsRetrieveTheSpecificChapter(String question, String title, String detail) {
        var results = guide.find(question);
        assertThat(results).isNotEmpty().hasSizeLessThanOrEqualTo(2);
        assertThat(results.getFirst().title()).isEqualTo(title);
        assertThat(results.getFirst().content()).contains(detail).doesNotContain("关键词：");
    }
}
