package com.ragpipeline.service;

import com.ragpipeline.model.Chunk;
import com.ragpipeline.model.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

@Service public class ChunkingService {
 private static final Pattern SECTION=Pattern.compile("^\\s*(\\d+(?:\\.\\d+)+\\s+.+?)\\s*$"), MARKDOWN=Pattern.compile("^\\s*#{1,6}\\s+(.+?)\\s*$");
 @Value("${rag.chunking.fixed-size:512}") int size; @Value("${rag.chunking.overlap:64}") int overlap;
 public List<Chunk> chunk(Document document){return chunk(document,document.getChunkingStrategy());}
 public List<Chunk> chunk(Document document,String strategy){String text=Optional.ofNullable(document.getRawContent()).orElse("").trim();if(text.isEmpty())return List.of();String selected=Optional.ofNullable(strategy).orElse("RECURSIVE").toUpperCase(Locale.ROOT);List<Part> parts=switch(selected){case "FIXED_SIZE"->fixed(text);case "RECURSIVE","SEMANTIC"->recursive(text);default->throw new IllegalArgumentException("Unknown chunking strategy: "+strategy);};Set<String> seen=new HashSet<>();List<Chunk> result=new ArrayList<>();for(Part part:parts){String content=part.content().trim();if(content.isBlank())continue;result.add(Chunk.builder().document(document).content(content).chunkIndex(result.size()).sectionHeading(part.heading()).chunkingStrategy(selected).charCount(content.length()).tokenEstimate((content.length()+3)/4).isDuplicate(!seen.add(content)).build());}return result;}
 private List<Part> recursive(String text){List<Part> chunks=new ArrayList<>();String heading=null;StringBuilder current=new StringBuilder();for(String line:text.split("\\R")){String trimmed=line.trim();String candidate=heading(trimmed);if(candidate!=null){flush(chunks,current,heading);heading=candidate;continue;}if(trimmed.isBlank())continue;append(chunks,current,trimmed,heading);}flush(chunks,current,heading);return chunks;}
 private List<Part> fixed(String text){List<Part> chunks=new ArrayList<>();for(int start=0;start<text.length();start+=Math.max(1,size-overlap))chunks.add(new Part(null,text.substring(start,Math.min(text.length(),start+size))));return chunks;}
 private void append(List<Part> chunks,StringBuilder current,String text,String heading){if(current.length()>0&&current.length()+text.length()+1>size){String previous=current.toString();flush(chunks,current,heading);current.append(previous.substring(Math.max(0,previous.length()-overlap))).append(' ');}current.append(text).append(' ');}
 private void flush(List<Part> chunks,StringBuilder current,String heading){if(current.length()>0)chunks.add(new Part(heading,current.toString()));current.setLength(0);}
 private String heading(String text){var numeric=SECTION.matcher(text);if(numeric.matches())return numeric.group(1);var markdown=MARKDOWN.matcher(text);return markdown.matches()?markdown.group(1):null;}
 private record Part(String heading,String content){}
}
