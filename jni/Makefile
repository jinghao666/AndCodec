# Makefile for systems with GNU tools
CC 	=	gcc
CFLAGS	=	-O2 -Wall -g -W -Wshadow -Ix264/include -Iffmpeg/include
#LIBS	=	-lpthread

FFMPEG_INC=ffmpeg/include
X264_INC=x264/include

OBJS	=	main.o andtunables.o andparseconf.o \
		easyencoder.o easydecoder.o andfifobuffer.o andstack.o \
		andlog.o andstr.o andsysutil.o andutility.o \
		x264/lib/local/libx264.dll.a ffmpeg/lib/local/*.a

.c.o:
	$(CC) -c $*.c $(CFLAGS) $(IFLAGS)

andcodec: $(OBJS) 
	$(CC) -o debug/testapp $(OBJS) $(LINK) $(LIBS) $(LDFLAGS)
		
clean:
	rm -rf *.o debug/testapp
	

