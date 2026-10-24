import club.xiaojiawei.svg.*;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.image.*;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.geometry.Rectangle2D;
import org.girod.javafx.svgimage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
public class EditorCorpusCheck {
 static String csv(String x){return "\""+x.replace("\"","\"\"").replace('\n',' ').replace('\r',' ')+"\"";}
 public static void main(String[] args)throws Exception{
  String provider=args[0];Path input=Path.of(args[1]),output=Path.of(args[2]);Files.createDirectories(output);
  Platform.startup(()->Platform.setImplicitExit(false));
  var task=new FutureTask<Void>(()->{
   List<String> rows=new ArrayList<>(List.of("provider,file,result,detail"));
   try(var files=Files.list(input)){
    for(Path file:files.filter(p->p.toString().endsWith(".svg")).sorted().toList()){
     Node node=null;int rasterHeight=256;
     try{
      if(provider.startsWith("native")){
       var view=new SvgView(SvgLoader.load(file,new SvgOptions(true)));view.setAnimationAutoPlay(false);
       double w=view.prefWidth(-1),h=view.prefHeight(-1);if(w<=0||h<=0)throw new IllegalStateException("No natural size");
       view.resize(w,h);view.getTransforms().add(new javafx.scene.transform.Scale(256/w,256/w));
       rasterHeight=Math.max(1,Math.min(2048,(int)Math.ceil(h*256/w)));node=view;
      }else{
       var parameters=LoaderParameters.createWidthParameters(256);parameters.autoStartAnimations=false;
       var image=org.girod.javafx.svgimage.SVGLoader.load(file.toUri().toURL(),parameters);node=image;
       double w=image.getViewportWidth(),h=image.getViewportHeight();
       if(w>0&&h>0)rasterHeight=Math.max(1,Math.min(2048,(int)Math.ceil(256*h/w)));
      }
      if(node==null)throw new IllegalStateException("Null node");
      node.setManaged(false);Pane root=new Pane(node);root.resize(256,rasterHeight);new Scene(root,256,rasterHeight);
      root.applyCss();root.layout();if(node instanceof Parent parent)parent.layout();
      var params=new SnapshotParameters();params.setFill(Color.TRANSPARENT);params.setViewport(new Rectangle2D(0,0,256,rasterHeight));
      var image=root.snapshot(params,new WritableImage(256,rasterHeight));
      int visible=0;var pixels=image.getPixelReader();
      var out=new BufferedImage(256,rasterHeight,BufferedImage.TYPE_INT_ARGB);
      for(int y=0;y<rasterHeight;y++)for(int x=0;x<256;x++){int argb=pixels.getArgb(x,y);out.setRGB(x,y,argb);if((argb>>>24)>20)visible++;}
      if(visible==0)throw new IllegalStateException("Empty raster");
      ImageIO.write(out,"png",output.resolve(file.getFileName().toString()+".png").toFile());
      rows.add(csv(provider)+","+csv(file.getFileName().toString())+",NONEMPTY,"+csv("nonempty pixels="+visible+"; not a reference-image fidelity check"));
     }catch(Exception ex){rows.add(csv(provider)+","+csv(file.getFileName().toString())+","+("Empty raster".equals(ex.getMessage())?"EMPTY":"ERROR")+","+csv(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage()));}
     finally{if(node instanceof SvgView view)view.setSource(null);else if(node instanceof SVGImage image)image.stopAnimations();}
    }
   }
   Files.write(output.resolve("results.csv"),rows);return null;
  });
  Platform.runLater(task);
  try{task.get(90,TimeUnit.SECONDS);}finally{Platform.exit();}
 }
}
