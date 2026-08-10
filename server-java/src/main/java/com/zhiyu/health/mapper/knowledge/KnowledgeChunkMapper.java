package com.zhiyu.health.mapper.knowledge;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.knowledge.KnowledgeChunk;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /** 事务内批量写入 chunk + 向量字面量（pgvector 接受 '[v1,v2,...]' 文本）。 */
    @Insert(
            """
            <script>
            INSERT INTO knowledge_chunks (department, title, content, vector, document_id)
            VALUES
            <foreach collection="chunks" item="c" separator=",">
                (#{c.department}, #{c.title}, #{c.content}, #{c.vectorLiteral}::vector, #{c.documentId})
            </foreach>
            </script>
            """)
    int batchInsertWithVector(@Param("chunks") List<ChunkInsert> chunks);

    /** 物理删除指定文档的全部 chunk 行（归档/重切前清理，检索 SQL 零改动）。 */
    @Delete("DELETE FROM knowledge_chunks WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") long documentId);

    /** 统计指定文档的 chunk 数（状态更新与校验用）。 */
    @Select("SELECT count(*) FROM knowledge_chunks WHERE document_id = #{documentId}")
    int countByDocumentId(@Param("documentId") long documentId);

    /** chunk 写入载体：vectorLiteral 为 pgvector 文本字面量 '[v1,v2,...]'。 */
    record ChunkInsert(String department, String title, String content, String vectorLiteral, Long documentId) {}
}
