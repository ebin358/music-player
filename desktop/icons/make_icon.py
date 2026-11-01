import sys
from PIL import Image, ImageDraw, ImageFilter
SS=2; N=1024*SS
def sc(v): return int(v*SS)
img=Image.new('RGBA',(N,N),(0,0,0,0))
# macOS grid: 824 body, 100 inset, ~185 corner radius
body=(sc(100),sc(100),sc(924),sc(924)); r=sc(185)
# plate: vertical gradient #333 -> #161616
grad=Image.new('RGBA',(N,N))
gd=ImageDraw.Draw(grad)
for y in range(N):
    t=min(max((y-sc(100))/sc(824),0),1)
    v=int(52*(1-t)+20*t)
    gd.line([(0,y),(N,y)],fill=(v,v+1,v+3,255))
mask=Image.new('L',(N,N),0); ImageDraw.Draw(mask).rounded_rectangle(body,r,fill=255)
shadow=Image.new('RGBA',(N,N),(0,0,0,0))
ImageDraw.Draw(shadow).rounded_rectangle((body[0],body[1]+sc(14),body[2],body[3]+sc(14)),r,fill=(0,0,0,110))
shadow=shadow.filter(ImageFilter.GaussianBlur(sc(18)))
img.alpha_composite(shadow)
plate=Image.new('RGBA',(N,N),(0,0,0,0)); plate.paste(grad,(0,0),mask)
img.alpha_composite(plate)
# subtle top highlight edge
edge=Image.new('RGBA',(N,N),(0,0,0,0))
ImageDraw.Draw(edge).rounded_rectangle(body,r,outline=(255,255,255,28),width=sc(3))
img.alpha_composite(edge)
# wave: phosphor wave-square centerline in a 256 box: (24,128)->(24,72)->(128,72)->(128,184)->(232,184)->(232,128)
box=sc(560); ox=(N-box)//2; oy=(N-box)//2
pts=[(24,128),(24,72),(128,72),(128,184),(232,184),(232,128)]
P=[(ox+x*box/256, oy+y*box/256) for x,y in pts]
w=int(16*box/256)
def stroke(color,width):
    layer=Image.new('RGBA',(N,N),(0,0,0,0)); d=ImageDraw.Draw(layer)
    d.line(P,fill=color,width=width,joint='curve')
    for (x,y) in [P[0],P[-1]]: d.ellipse((x-width/2,y-width/2,x+width/2,y+width/2),fill=color)
    return layer
glow=stroke((61,204,96,255),int(w*2.2)).filter(ImageFilter.GaussianBlur(sc(26)))
glow.putalpha(glow.getchannel('A').point(lambda a:int(a*0.75)))
img.alpha_composite(glow)
img.alpha_composite(stroke((128,239,162,255),w))
out=img.resize((1024,1024),Image.LANCZOS)
out.save('icon_1024.png')
