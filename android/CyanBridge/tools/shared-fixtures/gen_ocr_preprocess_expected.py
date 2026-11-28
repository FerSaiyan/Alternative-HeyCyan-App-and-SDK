#!/usr/bin/env python3
"""Prints the OpenCV expectations used by the shared ImageResizeTest (Case rows) and PpOcrPreprocessTest (pixel
values): cv2.INTER_LINEAR on a deterministic RGB pattern, and the PP-OCRv6 det/rec preprocessing of
cyanbridge-npu-models cpu_gpu/ANY/ocr_spec.json as the OCR bench's ocrlib.py implements it.

Usage: ~/anaconda3/envs/LocalGPT_llama2/bin/python -I tools/shared-fixtures/gen_ocr_preprocess_expected.py
(any Python with numpy + opencv-python 4.x; checked with 4.10 on x86-64)
"""
import numpy as np, cv2, math
def pattern(w,h):
    img=np.zeros((h,w,3),np.uint8)  # RGB
    for y in range(h):
        for x in range(w):
            img[y,x]=((x*37+y*91+11)%256, (x*x*7+y*13+101)%256, (x*y*29+53)%256)
    return img
cases=[(13,7,29,11),(40,30,17,9),(64,32,32,16),(5,3,9,48),(1,1,3,2),(6,9,4,20)]
print("// resize cases: (srcW, srcH, dstW, dstH) -> RGB hex")
for (w,h,dw,dh) in cases:
    img=pattern(w,h)
    r=cv2.resize(img,(dw,dh),interpolation=cv2.INTER_LINEAR).reshape(dh,dw,3)
    print(f'Case({w}, {h}, {dw}, {dh}, "{r.tobytes().hex()}"),')
# det
def det(rgb):
    img=rgb[:,:,::-1]; h,w=img.shape[:2]; r=min(256/h,512/w); nh,nw=int(round(h*r)),int(round(w*r))
    c=np.zeros((256,512,3),np.uint8); c[:nh,:nw]=cv2.resize(np.ascontiguousarray(img),(nw,nh))
    x=c.astype(np.float32)/255.0; x=(x-np.array([0.485,0.456,0.406],np.float32))/np.array([0.229,0.224,0.225],np.float32)
    return x,(nw,nh,r)
def rec(rgb):
    img=np.ascontiguousarray(rgb[:,:,::-1]); h,w=img.shape[:2]; ratio=w/max(h,1); rw=max(min(320,int(math.ceil(48*ratio))),1)
    im=cv2.resize(img,(rw,48),interpolation=cv2.INTER_LINEAR).astype(np.float32); im=(im/255.0-0.5)/0.5
    out=np.zeros((48,320,3),np.float32); out[:,:rw]=im; return out,rw
x,info=det(pattern(20,4)); print('det 20x4',info)
for (y,xx) in [(0,0),(0,1),(50,300),(101,511),(102,0),(255,511)]:
    print(' det',y,xx,[repr(float(v)) for v in x[y,xx]])
print(' det solid', [repr(float(v)) for v in det(np.full((3,3,3),(200,100,50),np.uint8))[0][0,0]])
x,rw=rec(pattern(10,5)); print('rec 10x5 rw',rw)
for (y,xx) in [(0,0),(20,47),(47,95),(47,96),(0,319)]:
    print(' rec',y,xx,[repr(float(v)) for v in x[y,xx]])
for (w,h) in [(7,3),(1000,10),(3,48),(1,100),(320,48),(100,15)]:
    print(' rw',w,h,max(min(320,int(math.ceil(48*(w/max(h,1))))),1))
for (w,h) in [(20,4),(1280,720),(100,333),(3,1)]:
    r=min(256/h,512/w); print(' detsize',w,h,int(round(w*r)),int(round(h*r)))
