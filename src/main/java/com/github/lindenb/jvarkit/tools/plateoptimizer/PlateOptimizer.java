/*
The MIT License (MIT)

Copyright (c) 2019 Pierre Lindenbaum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

*/
package com.github.lindenb.jvarkit.tools.plateoptimizer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.beust.jcommander.Parameter;
import com.github.lindenb.jvarkit.io.IOUtils;
import com.github.lindenb.jvarkit.lang.CharSplitter;
import com.github.lindenb.jvarkit.lang.JvarkitException;
import com.github.lindenb.jvarkit.lang.StringUtils;
import com.github.lindenb.jvarkit.util.jcommander.Launcher;
import com.github.lindenb.jvarkit.util.jcommander.Program;
import com.github.lindenb.jvarkit.util.log.Logger;

import htsjdk.samtools.util.RuntimeIOException;

/**
BEGIN_DOC

## Example

```

```

END_DOC
 */
@Program(
		name="plateoptimizer",
		creationDate="20191125",
		modificationDate="20191125",
		generate_doc=false,
		keywords= {"plate"}
		)
public class PlateOptimizer extends Launcher {
	private static final Logger LOG = Logger.build(PlateOptimizer.class).make();
	@Parameter(names= {"-o","--ouput"},description="Output prefix",required=true)
	private String output;
	@Parameter(names= {"-f","--fields"},description="Ordered critera",required=true)
	private Path criteraPath=null;
	
	private int bonusDiagonal = 0;

	private String header[]=null;
	private final List<Content> all_contents = new ArrayList<>();
	private final List<Criteria> all_criterias = new ArrayList<>();
	private volatile int ctrlc_catched_count=5;
	private final Random random = new Random(System.currentTimeMillis());
	
	private class Content 
		{
		final String tokens[];
		Content(final String tokens[]) {
			this.tokens=tokens;
			}
		int score(final Content other) {
			if(this==other) return 0;
			int n=0;
			for(final Criteria c: all_criterias) {
				if(!c.isSameContent(this,other)) n+=c.score;
				}
			return n;
			}
		
		boolean isOptimal(final Content other) {
			if(this==other) return true;
			for(final Criteria c: all_criterias) {
				if(c.isSameContent(this,other)) return false;
				}
			return true;
			}
		@Override
		public String toString() {
			return String.join("\t", tokens);
			}
		}
	
	private class Criteria {
		int column;
		int score=-10;
		Predicate<String> emptyTest = S->StringUtils.isBlank(S) || S.equals("NA") || S.equals("N/A");
		
		BiPredicate<String,String> comparator = (A,B)->{
			if(emptyTest.test(A) && emptyTest.test(B)) return false;
			return A.equals(B);
			};
		boolean isSameContent(final Content c1,final Content c2) {
			String s1 = c1.tokens[this.column];
			String s2 = c2.tokens[this.column];
			return this.comparator.test(s1, s2);
		}
	}
	
	private class Cell 
		{
		final Plate plate;
		final int x;
		final int y;
		Content content = null;
		Cell(final Plate plate,int x,int y) {
			this.plate = plate;
			this.x=x;
			this.y=y;
			}
		String getLabel() {
			return (char)((int)'A'+y)+String.format("%02d", x+1);
			}
		int getScore() {
			if(this.content==null) return 0;
			int score=0;
			for(int x=-1;x<=1;++x)
				{
				for(int y=-1;y<=1;++y)
					{
					if(x==0 && y==0) continue;
					final Cell neighbour = this.plate.get(this.x + x, this.y + y);
					if( neighbour == null ) continue;
					if( neighbour.content==null)
						{
						score += -1;
						}
					else if(!neighbour.content.isOptimal(this.content))
						{
						final int n= neighbour.content.score(this.content);
						if(n>0) throw new IllegalStateException();
						if(x==0 || y==0)
							{
							score += n;
							}
						else //diagonal
							{
							score += n + bonusDiagonal;
							}
						}
					}
				}
			return score;
			}
		
		}
	private class Plate {
		final String name;
		final Cell cells[] = new Cell[96];
		Plate(final String name) {
			this.name  = name;
			int i=0;
			for(int y=0;y<8;++y)
				for(int x=0;x<12;++x)
					{
					final Cell cell=new Cell(this,x,y);
					this.cells[i++] = cell;
					}
			}
		Plate(final Plate cp) {
			this(cp.name);
			for(int i=0;i< this.cells.length;i++) this.cells[i].content=cp.cells[i].content;
			}
		Cell get(int x,int y) {
			if(x<0 || x>=12) return null;
			if(y<0 || y>=8) return null;
			return this.cells[y*12+x];
			}
		void writeXML(final XMLStreamWriter w) throws XMLStreamException
			{
			w.writeStartElement("table");
			
			w.writeStartElement("caption");
			w.writeCharacters(this.name);
			w.writeEndElement();
			
			
			w.writeStartElement("thead");
			
			w.writeStartElement("tr");
			w.writeEmptyElement("th");
			for(int x=0;x< 12;++x) {
				w.writeStartElement("th");
				w.writeCharacters(String.format("%02d", x+1));
				w.writeEndElement();//th
				}
			w.writeEndElement();//tr
			w.writeEndElement();//thead
			
			w.writeStartElement("tbody");
			for(int y=0;y< 8;++y) {
				w.writeStartElement("tr");
				w.writeStartElement("th");
				w.writeCharacters(String.valueOf( (char)((int)'A'+y)));
				w.writeEndElement();//th
				for(int x=0;x< 12;++x) {
					w.writeStartElement("td");
					final Cell cell = this.get(x, y);
					if(cell.content!=null) {
						for(final Criteria c:all_criterias) {
							w.writeCharacters(cell.content.tokens[c.column]+";");
							}
						}
					w.writeEndElement();//td
					}
				w.writeEndElement();//tr
				}
			
			w.writeEndElement();//tbody
			w.writeEndElement();
			}
	
	}
	
	private class Solution {
		final long id;
		final List<Plate> plates = new ArrayList<>();
		Integer score = null;
		Solution(final long id) {
			this.id = id;
		}
		
		Solution computeScore() {
			this.score = this.plates.
					stream().
					flatMap(P->Arrays.stream(P.cells)).
					mapToInt(C->C.getScore()).
					sum();
			return this;
			}
		
		Solution fill() {
			final int n_plates = (int)Math.ceil(PlateOptimizer.this.all_contents.size()/96.0);
			if(n_plates==0) throw new IllegalStateException();
			final List<Cell> cells = new ArrayList<>(PlateOptimizer.this.all_contents.size());
			for(int p=0;p<n_plates;++p)
				{
				final Plate plate = new Plate("PLATE_ID"+(p+1));
				this.plates.add(plate);
				//https://stackoverflow.com/questions/1374126/
				cells.addAll(Arrays.asList(plate.cells));
				}
			Collections.shuffle(cells,PlateOptimizer.this.random);
			int i=0;
			for(final Content content: PlateOptimizer.this.all_contents) {
				cells.get(i).content  = content;
				i++;
				}
			
			return this.computeScore();
			}
		
		Solution mute(long iter_id)
			{
			final Solution sol = new Solution(iter_id);
			
			sol.plates.addAll(
				this.plates.stream().
				map(P->new Plate(P)).
				collect(Collectors.toList())
				);
			
			final List<Cell> cells = sol.plates.stream().
					flatMap(P->Arrays.stream(P.cells)).
					collect(Collectors.toCollection(ArrayList::new));				
			int repeat = 1 +  PlateOptimizer.this.random.nextInt(5);
			
			if(cells.size()!=0) //not all optimal
				{
				int max_fail = 0;
				while(max_fail<10_000 && repeat>0) {
					int index1= PlateOptimizer.this.random.nextInt(cells.size());
					int index2= PlateOptimizer.this.random.nextInt(cells.size());
					if(index1==index2) {
						max_fail++;
						continue;
						}
					final Content c1 = cells.get(index1).content;
					final Content c2 = cells.get(index2).content;
					if(c1==null && c2==null)  {
						max_fail++;
						continue;
						}
					cells.get(index1).content = c2;
					cells.get(index2).content = c1;
					repeat--;
					}
				}
			return sol.computeScore();
			}
		
		private void save() {
			try {
				try(PrintWriter w= new PrintWriter(output+".txt")) {
					w.print("#plate\tcell");
					for(String header:PlateOptimizer.this.header) w.print("\t"+header);
					w.println();
					for(final Plate p:this.plates) {
						
						for(final Cell cell:p.cells) {
							w.print(p.name);
							w.print("\t");
							w.print(cell.getLabel());
							w.print("\t");
							if(cell.content!=null) {
								w.print(String.join("\t", cell.content.tokens));
								}
							w.println();
							}
						}
					w.flush();
					}
				final XMLOutputFactory xof = XMLOutputFactory.newFactory();
				try(PrintWriter pw= new PrintWriter(output+".html")) {
					final XMLStreamWriter w=xof.createXMLStreamWriter(pw);
					w.writeStartElement("html");
					
					w.writeStartElement("head");
					w.writeStartElement("title");
					w.writeCharacters("Iteration :"+this.id+" Score:"+this.score);
					w.writeEndElement();//title
					w.writeStartElement("style");
					w.writeCharacters("table{font-family:Arial,Verdana,sans-serif;color:darkgray;font-size:14px;border-collapse:collapse;border:1px solid black;padding:5px;margin:5px;}");
					w.writeEndElement();//title
					
					w.writeEndElement();//head
					
					w.writeStartElement("body");
					w.writeStartElement("h2");
					w.writeCharacters("Iteration :"+this.id+" Score:"+this.score);
					w.writeEndElement();//h2
					w.writeStartElement("div");
					for(final Plate p:this.plates) {
						p.writeXML(w);
					}
					w.writeEndElement();
					w.writeEmptyElement("hr");
					w.writeEndElement();//body
					w.writeEndElement();//html
					w.flush();
					w.close();
					}

				}
			catch(IOException|XMLStreamException err) {
				throw new RuntimeIOException(err);
				}
			}
		@Override
		public String toString() {
			return String.valueOf(this.score);
			}
		}
	
	private Solution best=null;
	
	void iteration(long iter_id) {
		Solution sol = null;
		if(iter_id == 0 || iter_id%20==0 || this.best==null)
			{
			sol = new Solution(iter_id).fill();
			}
		else
			{
			sol = this.best.mute(iter_id);
			}

		

		if(this.best==null || sol.score> this.best.score)
			{
			this.best=sol;
			LOG.info("OK best score:" + this.best.score);
			sol.save();
			}
		}
	
	
	@Override
	public int doWork(final List<String> args) {
		try {
			final Map<String,Integer>  column2index= new HashMap<>();
			final CharSplitter tab = CharSplitter.TAB;
			final String input = oneFileOrNull(args);
			try(BufferedReader br = (input==null?
					IOUtils.openStreamForBufferedReader(stdin()):
					IOUtils.openURIForBufferedReading(input)
					)) {
				String line = br.readLine();
				if(line==null) {
					LOG.error("cannot read first line");
				}
				this.header = tab.split(line);
				for(int i=0;i< this.header.length;++i) column2index.put(this.header[i],i);
				LOG.info("header: "+String.join(";", this.header));
				while((line=br.readLine())!=null) {
					if(StringUtils.isBlank(line)) continue;
					final String tokens[]=tab.split(line);
					if(tokens.length!=this.header.length) throw new JvarkitException.TokenErrors(this.header.length, tokens);
					final Content content = new Content(tokens);
					this.all_contents.add(content);
				}
			}
			if(this.all_contents.isEmpty()) {
				LOG.error("no data defined");
				return -1;
			}
			try(BufferedReader br= IOUtils.openPathForBufferedReading(this.criteraPath)) {
				Criteria curr = null;
				for(;;) {
					String line  = br.readLine();
					if(line==null) break;
					if(StringUtils.isBlank(line)) {
						curr=null;
						continue;
					}
					if(curr==null || line.indexOf(':')==-1) {
						curr= new Criteria();
						if(!column2index.containsKey(line)) {
							LOG.error("field "+line+" is not part of:\n"+String.join("\n",column2index.keySet()));
							return -1;
						}
						curr.column = column2index.get(line);
						this.all_criterias.add(curr);
						final int final_col = curr.column;
						LOG.info("criteria["+this.all_criterias.size()+"] on column "+line+" with :"+
								String.join(" ", this.all_contents.stream().map(K->K.tokens[final_col]).collect(Collectors.toSet())));
					} else
						{
						int colon= line.indexOf(":");
						if(colon==-1) {
							LOG.error("':' missing in "+line);
							return -1;
							}
						String left = line.substring(0,colon).trim();
						String right = line.substring(colon+1).trim();
						if(left.equals("score")) {
							curr.score = Integer.parseInt(right);
							if(curr.score>=0) {
								LOG.error("score must be <0 in "+line);
								return -1;
								}
							}
						else
							{
							LOG.error("unknow modifier in "+line);
							return -1;
							}
						}
					}
				}
			
			Runtime.getRuntime().addShutdownHook(new Thread() {
			        public void run() {
			        	ctrlc_catched_count --;
			        	if(ctrlc_catched_count==0) {
			                LOG.warn("Shutting down ...");
				        	Runtime.getRuntime().removeShutdownHook(this);
			        		}
			        	else
			        		{
			        		LOG.info("press again "+ctrlc_catched_count+" times.");
			        		}
			        }
			    });
			long n_iteration = 0L;
			while(this.ctrlc_catched_count>0) {
				
				iteration(++n_iteration);
				if(this.best!=null && this.best.score>=0) break;
				}
			ctrlc_catched_count=0;
			LOG.info("done");
			return 0;
			}
		catch(final Throwable err) {
			LOG.error(err);
			return -1;
			}
		finally {
			
			}
		}
	
	public static void main(final String[] args) {
		new PlateOptimizer().instanceMainWithExit(args);
	}
}