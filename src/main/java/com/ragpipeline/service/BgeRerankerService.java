package com.ragpipeline.service;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import com.ragpipeline.model.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;

@Slf4j @Service
public class BgeRerankerService {
 private final OrtSession session; private final OrtEnvironment environment; private final HuggingFaceTokenizer tokenizer;
 @Value("${bge.reranker.max-length:512}") private int maxLength;
 public BgeRerankerService(@Qualifier("bgeRerankerSession") OrtSession session, OrtEnvironment environment, @Qualifier("bgeRerankerTokenizer") HuggingFaceTokenizer tokenizer){this.session=session;this.environment=environment;this.tokenizer=tokenizer;}
 public List<RetrievedChunk> rerank(String question,List<RetrievedChunk> candidates,int topK){return candidates.stream().peek(c->c.setRerankerScore(scorePair(question,c.getContent()))).sorted(Comparator.comparingDouble(RetrievedChunk::getRerankerScore).reversed()).limit(topK).toList();}
 public List<double[]> scoreAll(String question,List<String> passages){return passages.stream().map(p->new double[]{scorePair(question,p)}).toList();}
 public double scorePair(String textA,String textB){try{Encoding encoding=tokenizer.encode(textA,textB);int length=Math.min(maxLength,(int)encoding.getIds().length);long[][] ids={Arrays.copyOf(encoding.getIds(),length)},masks={Arrays.copyOf(encoding.getAttentionMask(),length)},types={Arrays.copyOf(encoding.getTypeIds(),length)};try(OnnxTensor id=OnnxTensor.createTensor(environment,ids);OnnxTensor mask=OnnxTensor.createTensor(environment,masks);OnnxTensor type=OnnxTensor.createTensor(environment,types)){Map<String,OnnxTensor> inputs=new HashMap<>();Set<String> names=session.getInputNames();if(names.contains("input_ids"))inputs.put("input_ids",id);if(names.contains("attention_mask"))inputs.put("attention_mask",mask);if(names.contains("token_type_ids"))inputs.put("token_type_ids",type);try(OrtSession.Result result=session.run(inputs)){float[][] logits=(float[][])result.get("logits").orElseThrow(()->new IllegalStateException("Missing logits; outputs: "+session.getOutputNames())).getValue();return 1d/(1d+Math.exp(-logits[0][0]));}}}catch(Exception error){log.error("BGE reranker inference failed. Inputs={}, outputs={}",session.getInputNames(),session.getOutputNames(),error);throw new IllegalStateException("BGE reranker inference failed",error);}}
}
