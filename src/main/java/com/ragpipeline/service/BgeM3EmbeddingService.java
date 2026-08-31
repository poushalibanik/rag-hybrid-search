package com.ragpipeline.service;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import com.ragpipeline.model.BgeEmbedding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;

/** Supports the installed BGE-M3 ONNX export: sentence_embedding + token_embeddings. */
@Slf4j @Service
public class BgeM3EmbeddingService {
    private final OrtSession session; private final OrtEnvironment environment; private final HuggingFaceTokenizer tokenizer;
    @Value("${bge.m3.max-length:8192}") private int maxLength;
    public BgeM3EmbeddingService(@Qualifier("bgeM3Session") OrtSession session, OrtEnvironment environment, @Qualifier("bgeM3Tokenizer") HuggingFaceTokenizer tokenizer) { this.session=session; this.environment=environment; this.tokenizer=tokenizer; }
    public BgeEmbedding embed(String text) { return embedBatch(List.of(text)).getFirst(); }
    public List<BgeEmbedding> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        try {
            Encoding[] encodings=tokenizer.batchEncode(texts.toArray(String[]::new));
            int length=Math.min(maxLength,Arrays.stream(encodings).mapToInt(e->(int)e.getIds().length).max().orElse(1));
            long[][] ids=new long[texts.size()][length], masks=new long[texts.size()][length], types=new long[texts.size()][length];
            for(int row=0;row<encodings.length;row++){copy(encodings[row].getIds(),ids[row],length);copy(encodings[row].getAttentionMask(),masks[row],length);if(encodings[row].getTypeIds()!=null)copy(encodings[row].getTypeIds(),types[row],length);}
            try(OnnxTensor id=OnnxTensor.createTensor(environment,ids); OnnxTensor mask=OnnxTensor.createTensor(environment,masks); OnnxTensor type=OnnxTensor.createTensor(environment,types)) {
                Map<String,OnnxTensor> input=new HashMap<>(); Set<String> names=session.getInputNames();
                if(names.contains("input_ids"))input.put("input_ids",id); if(names.contains("attention_mask"))input.put("attention_mask",mask); if(names.contains("token_type_ids"))input.put("token_type_ids",type);
                try(OrtSession.Result result=session.run(input)) {
                    Object value=result.get("sentence_embedding").orElseThrow(()->new IllegalStateException("Missing sentence_embedding; available outputs: "+session.getOutputNames())).getValue();
                    float[][] dense=(float[][])value; List<BgeEmbedding> output=new ArrayList<>();
                    for(int row=0;row<texts.size();row++)output.add(BgeEmbedding.builder().denseVector(normalize(dense[row])).sparseVector(tokenFrequency(ids[row],masks[row])).originalText(texts.get(row)).build());
                    return output;
                }
            }
        } catch(Exception error) { log.error("BGE-M3 inference failed. Inputs={}, outputs={}",session.getInputNames(),session.getOutputNames(),error); throw new IllegalStateException("BGE-M3 inference failed",error); }
    }
    private Map<Integer,Float> tokenFrequency(long[] ids,long[] masks){Map<Integer,Float> result=new HashMap<>();for(int i=0;i<ids.length;i++)if(masks[i]!=0&&ids[i]>0)result.merge(Math.toIntExact(ids[i]),1f,Float::sum);float norm=0;for(float value:result.values())norm+=value*value;norm=(float)Math.sqrt(norm);if(norm>0)for(var entry:result.entrySet())entry.setValue(entry.getValue()/norm);return result;}
    private void copy(long[] source,long[] destination,int length){System.arraycopy(source,0,destination,0,Math.min(source.length,length));}
    private float[] normalize(float[] vector){double sum=0;for(float value:vector)sum+=value*value;if(sum==0)return vector;float[] normalized=new float[vector.length];double norm=Math.sqrt(sum);for(int i=0;i<vector.length;i++)normalized[i]=(float)(vector[i]/norm);return normalized;}
}
